// The nav's availability-alerts row.
//
// Port of web/topbar/alerts.js' rendering half: a bar that appears when the user has
// any watches, expanding into a table of them with per-row pause/resume, edit and
// delete — and the Slack deep-link behaviour that points at a row without acting on it.
//
// The editor is 4d's `WatchEditor`, which is the second consumer that port was written
// for: the vanilla mounted the same `mountWatchEditor` controller here and in the
// availability grid.
import { useEffect, useRef, useState } from 'react';
import { signIn } from '@/api/auth-api';
import { getWatch, updateWatch, type Watch } from '@/api/watches-api';
import { useQuery } from '@tanstack/react-query';
import { WatchEditor } from '@/features/availability/WatchEditor';
import { normalizeWatchCapabilities } from '@/features/availability/watch-windows';
import { formatWatchDate, relativeTime } from '@/lib/watch-format';
import {
  TRIGGER_KIND_ATC,
  TRIGGER_KIND_EMAIL_NOTIFY,
  TRIGGER_KIND_SLACK_NOTIFY,
  type TriggerPayload,
} from '@/lib/watch-triggers';
import { queryKeys } from '@/queries/keys';
import { useMapStore } from '@/stores/mapStore';
import {
  FOCUS_HIGHLIGHT_MS,
  alertName,
  barLabel,
  clearAlertDeepLink,
  doneKind,
  readAlertDeepLink,
  type AlertAction,
} from './alert-rows';
import { useAlertMutations, useAlerts } from './useAlerts';
import './alerts.css';

export function AlertsPanel() {
  const { watches, counts, poiNames, signedOut } = useAlerts();
  const mutations = useAlertMutations();
  const selectPoi = useMapStore((s) => s.selectPoi);

  const [expanded, setExpanded] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [focus, setFocus] = useState<{ watchId: string; action: AlertAction | null } | null>(null);

  /**
   * The Slack deep link, read once per mount and then stripped from the URL.
   *
   * Stripped so a refresh or a back-nav does not re-focus the row, and read in an
   * effect rather than during render because it writes history. The highlight is a
   * transient cue: it expires on its own after `FOCUS_HIGHLIGHT_MS`.
   */
  const applied = useRef(false);
  useEffect(() => {
    if (applied.current) return;
    applied.current = true;
    const link = readAlertDeepLink();
    if (!link) return;
    clearAlertDeepLink();
    setFocus(link);
    setExpanded(true);
  }, []);

  useEffect(() => {
    if (!focus) return;
    const timer = setTimeout(() => setFocus(null), FOCUS_HIGHLIGHT_MS);
    return () => clearTimeout(timer);
  }, [focus]);

  /** Scroll the deep-linked row into view once it exists. */
  const focusedRow = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!focus) return;
    focusedRow.current?.scrollIntoView?.({ block: 'nearest' });
  }, [focus, watches.length]);

  // Signed out WITH a deep link is the one case that still shows something: the user
  // followed a Slack card to a watch they own, so the useful answer is a way in — not
  // an empty nav. Signed out without one renders nothing at all.
  if (signedOut) {
    return focus ? <SignInPrompt /> : null;
  }
  // No watches, no row. The panel is additive: it must not take space to say "none".
  if (counts.total === 0) return null;

  return (
    <div className="tb-alerts visible" id="tb-alerts">
      <button
        type="button"
        className="tb-alerts-bar"
        aria-expanded={expanded}
        onClick={() => setExpanded((current) => !current)}
      >
        <span className="tb-alerts-bell" aria-hidden="true">
          🔔
        </span>
        <span className="tb-alerts-label">{barLabel(counts)}</span>
        <ChevronIcon />
      </button>

      {expanded ? (
        <div className="tb-alerts-table" role="table" aria-label="Availability alerts">
          <div className="tb-alerts-row tb-alerts-header" role="row">
            <span role="columnheader">POI</span>
            <span role="columnheader">Date</span>
            <span role="columnheader">Trigger</span>
            <span role="columnheader">Last checked</span>
            <span role="columnheader" className="tb-alerts-actions-col" />
          </div>

          {watches.map((watch) => {
            const focused = focus?.watchId === String(watch.id);
            return (
              <div key={watch.id}>
                <div
                  className={`tb-alerts-row${statusClass(watch)}${focused ? ' is-focus' : ''}`}
                  role="row"
                  data-id={watch.id}
                  data-poi={watch.poi_id ?? ''}
                  ref={focused ? focusedRow : undefined}
                  // Clicking the row opens the POI's drawer, which is where the
                  // availability this watch is about lives.
                  onClick={() => {
                    if (watch.poi_id != null) selectPoi(watch.poi_id);
                  }}
                >
                  <span className="tb-alerts-poi" role="cell" title={alertName(watch, poiNames)}>
                    {alertName(watch, poiNames)}
                  </span>
                  <span className="tb-alerts-date" role="cell">
                    {formatWatchDate(watch.start_date) ?? '—'}
                  </span>
                  <span className="tb-alerts-trigger" role="cell">
                    <Triggers watch={watch} />
                  </span>
                  <span className="tb-alerts-checked" role="cell">
                    <LastChecked watch={watch} />
                  </span>
                  <span className="tb-alerts-actions" role="cell">
                    <RowActions
                      watch={watch}
                      armed={focused ? focus?.action ?? null : null}
                      busy={mutations.busy}
                      onEdit={() => setEditingId(watch.id)}
                      onSetStatus={(status) => void mutations.setStatus(watch.id, status)}
                      onDelete={() => {
                        if (editingId === watch.id) setEditingId(null);
                        void mutations.remove(watch.id);
                      }}
                    />
                  </span>
                </div>

                {editingId === watch.id ? (
                  <AlertEditorRow watchId={watch.id} onClose={() => setEditingId(null)} />
                ) : null}
              </div>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

/** Terminal watches show why they ended; live ones get their toggle. */
function RowActions({
  watch,
  armed,
  busy,
  onEdit,
  onSetStatus,
  onDelete,
}: {
  watch: Watch;
  armed: AlertAction | null;
  busy: boolean;
  onEdit: () => void;
  onSetStatus: (status: 'active' | 'paused') => void;
  onDelete: () => void;
}) {
  const stop = (run: () => void) => (event: React.MouseEvent) => {
    // The row itself opens the drawer, so every control has to keep its click.
    event.stopPropagation();
    run();
  };

  return (
    <>
      {watch.status === 'done' ? (
        doneKind(watch) === 'expired' ? (
          <span className="tb-alerts-status" title="Watch window ended without availability">
            ⌛
          </span>
        ) : (
          <span className="tb-alerts-status" title="Availability found">
            ✅
          </span>
        )
      ) : (
        <>
          <button
            type="button"
            className={`tb-alerts-act${armed === (watch.status === 'paused' ? 'resume' : 'pause') ? ' is-armed' : ''}`}
            title={watch.status === 'paused' ? 'Resume' : 'Pause'}
            aria-label={watch.status === 'paused' ? 'Resume watch' : 'Pause watch'}
            disabled={busy}
            onClick={stop(() => onSetStatus(watch.status === 'paused' ? 'active' : 'paused'))}
          >
            {watch.status === 'paused' ? '▶' : '⏸'}
          </button>
          <button
            type="button"
            className="tb-alerts-act"
            title="Edit"
            aria-label="Edit watch"
            onClick={stop(onEdit)}
          >
            ⚙
          </button>
        </>
      )}
      <button
        type="button"
        className={`tb-alerts-act tb-alerts-del${armed === 'delete' ? ' is-armed' : ''}`}
        title="Delete"
        aria-label="Delete watch"
        disabled={busy}
        onClick={stop(onDelete)}
      >
        🗑
      </button>
    </>
  );
}

/**
 * The editor for one watch, fetched on demand.
 *
 * `GET /api/watches/{id}` rather than the list row, because only the detail response
 * carries `watch_capabilities` — and the editor's whole job is to offer exactly the
 * triggers the provider can service.
 */
function AlertEditorRow({ watchId, onClose }: { watchId: number; onClose: () => void }) {
  const query = useQuery({
    queryKey: queryKeys.watches.detail(watchId),
    queryFn: ({ signal }) => getWatch(watchId, { signal }),
    retry: false,
  });
  const mutations = useAlertMutations();

  if (query.isPending) {
    return (
      <div className="tb-alerts-editor-row">
        <div className="tb-alerts-editor-loading">Loading watch…</div>
      </div>
    );
  }
  if (query.error || !query.data?.watch) {
    return (
      <div className="tb-alerts-editor-row">
        <div className="tb-alerts-editor-error">Could not load this watch.</div>
      </div>
    );
  }

  const watch = query.data.watch;
  return (
    <div className="tb-alerts-editor-row">
      <div className="tb-alerts-editor-host">
        <WatchEditor
          title={`Edit ${watch.poi_id != null ? `POI ${watch.poi_id}` : `watch #${watch.id}`}`}
          subtitle={watchWindow(watch)}
          watch={watch}
          capabilities={normalizeWatchCapabilities(query.data.watch_capabilities)}
          onSave={async (payload: TriggerPayload) => {
            await updateWatch(watch.id, payload);
            onClose();
          }}
          onRemove={async () => {
            await mutations.remove(watch.id);
            onClose();
          }}
          onClose={onClose}
        />
      </div>
    </div>
  );
}

/** "Aug 10 – Aug 12", or the one date a single-night watch covers. */
function watchWindow(watch: Watch): string {
  const start = formatWatchDate(watch.start_date);
  const end = formatWatchDate(watch.end_date);
  if (!start) return '';
  return end && end !== start ? `${start} – ${end}` : start;
}

/**
 * Trigger kinds as icons, from a table.
 *
 * Data-driven so a new kind renders without touching the row, which is what the
 * vanilla's `TRIGGER_HTML` map bought — and here an unknown kind renders as its own
 * text with no escaping question, because React does not interpolate markup.
 */
function Triggers({ watch }: { watch: Watch }) {
  const kinds = Array.isArray(watch.trigger_kinds) ? watch.trigger_kinds : [];
  if (kinds.length === 0) return <>—</>;
  return (
    <>
      {kinds.map((kind) => (
        <TriggerIcon key={kind} kind={kind} />
      ))}
    </>
  );
}

function TriggerIcon({ kind }: { kind: string }) {
  if (kind === TRIGGER_KIND_SLACK_NOTIFY) return <SlackIcon />;
  if (kind === TRIGGER_KIND_EMAIL_NOTIFY) return <EmailIcon />;
  if (kind === TRIGGER_KIND_ATC) return <span title="Add to cart">🛒 ATC</span>;
  return <span>{kind}</span>;
}

function LastChecked({ watch }: { watch: Watch }) {
  if (watch.last_run_status === 'failed') {
    return (
      <span className="tb-alerts-err" title={watch.last_run_error ?? undefined}>
        ⚠ error
      </span>
    );
  }
  if (!watch.last_run_at) return <span className="tb-alerts-faint">—</span>;
  return <span title={watch.last_run_at}>{relativeTime(watch.last_run_at)}</span>;
}

function SignInPrompt() {
  return (
    <div className="tb-alerts visible" id="tb-alerts">
      <div className="tb-alerts-signin-prompt">
        <p className="tb-alerts-signin-msg">Sign in to view this alert</p>
        <button type="button" className="tb-alerts-signin-btn" onClick={() => signIn()}>
          Sign in
        </button>
      </div>
    </div>
  );
}

const statusClass = (watch: Watch): string =>
  watch.status === 'paused' ? ' is-paused' : watch.status === 'done' ? ' is-done' : '';

function ChevronIcon() {
  return (
    <svg
      className="tb-alerts-chevron"
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <polyline points="6 9 12 15 18 9" />
    </svg>
  );
}

/** Slack's four-colour mark, inline so the row needs no network fetch. */
function SlackIcon() {
  return (
    <svg className="tb-alerts-slack" viewBox="0 0 122.8 122.8" role="img" aria-label="Slack">
      <title>Slack</title>
      <path
        fill="#E01E5A"
        d="M25.8 77.6c0 7.1-5.8 12.9-12.9 12.9S0 84.7 0 77.6s5.8-12.9 12.9-12.9h12.9v12.9zm6.5 0c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9v32.3c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V77.6z"
      />
      <path
        fill="#36C5F0"
        d="M45.2 25.8c-7.1 0-12.9-5.8-12.9-12.9S38.1 0 45.2 0s12.9 5.8 12.9 12.9v12.9H45.2zm0 6.5c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H12.9C5.8 58.1 0 52.3 0 45.2s5.8-12.9 12.9-12.9h32.3z"
      />
      <path
        fill="#2EB67D"
        d="M97 45.2c0-7.1 5.8-12.9 12.9-12.9s12.9 5.8 12.9 12.9-5.8 12.9-12.9 12.9H97V45.2zm-6.5 0c0 7.1-5.8 12.9-12.9 12.9s-12.9-5.8-12.9-12.9V12.9C64.7 5.8 70.5 0 77.6 0s12.9 5.8 12.9 12.9v32.3z"
      />
      <path
        fill="#ECB22E"
        d="M77.6 97c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9-12.9-5.8-12.9-12.9V97h12.9zm0-6.5c-7.1 0-12.9-5.8-12.9-12.9s5.8-12.9 12.9-12.9h32.3c7.1 0 12.9 5.8 12.9 12.9s-5.8 12.9-12.9 12.9H77.6z"
      />
    </svg>
  );
}

function EmailIcon() {
  return (
    <svg
      className="tb-alerts-email"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label="Email"
    >
      <title>Email</title>
      <rect width="20" height="16" x="2" y="4" rx="2" />
      <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
    </svg>
  );
}
