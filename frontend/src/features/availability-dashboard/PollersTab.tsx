import { useEffect, useState } from 'react';
import { Banner, Button, EmptyState, Select, Skeleton, Table, Tag } from '@ui';
import type { AvailabilityPoller } from '@/api/availability-dashboard-api';
import { formatTimestamp } from '@/lib/format';
import {
  COOLDOWN_STATUS,
  NOT_FOUND_STATUS,
  useForcePoller,
  usePollers,
  usePollersSummary,
  type ActiveFilter,
  type PollerFilters,
} from './useDashboard';
import { TAB_RUNS, type TabRoute } from './useTabRoute';
import type { ForcePollerCooldown } from '@/api/availability-dashboard-api';

/** Order matches the legacy select: any first, then the two concrete states. */
const ACTIVE_OPTIONS = [
  { value: '', label: 'any' },
  { value: 'true', label: 'active' },
  { value: 'false', label: 'dormant' },
];

/** The legacy select opened on `active`, not on `any`. */
const DEFAULT_FILTERS: PollerFilters = { active: 'true' };

const NONE = '—';

const COLUMNS = [
  { key: 'id', label: 'id' },
  { key: 'provider', label: 'provider' },
  { key: 'parentRef', label: 'parent ref' },
  { key: 'status', label: 'status' },
  { key: 'watches', label: 'watches' },
  { key: 'nextRun', label: 'next run' },
  { key: 'lastRun', label: 'last run' },
  { key: 'claimed', label: 'claimed' },
  { key: 'actions', label: 'actions' },
];

export interface PollersTabProps {
  route: TabRoute;
}

/**
 * Feedback for one "check now" press, keyed by poller id.
 *
 * Per-row rather than one banner because the legacy tab put the message in the
 * row it belonged to, and several rows can be in flight at once.
 *
 * **Cleared when fresh list data arrives**, which is what the legacy tab got for
 * free: its `refresh()` rebuilt `resultsEl.innerHTML`, destroying the feedback
 * span along with the row. Holding these in React state instead means they
 * outlive the row unless something clears them — and a permanent "queued" badge
 * beside a poller whose run happened long ago is worse than no badge, as is a
 * "try again in 42s" whose number never ticks down and never expires.
 */
type Feedback = Readonly<Record<string, string>>;

const CHECKING = 'checking…';
const QUEUED = 'queued';

/**
 * How long a row message stays up.
 *
 * Long enough to read, short enough that it cannot be mistaken for current state
 * — a "queued" badge beside a poller whose run happened long ago, or a "try again
 * in 42s" whose number never ticks down, is worse than no badge at all.
 */
const FEEDBACK_TTL_MS = 6000;

/**
 * Rebuild of web/components/availability/pollers-tab.js.
 *
 * A poller is the coalesced per-(provider, parent_ref) schedulable; many watches
 * share one. Clicking an id goes to the runs tab filtered to that poller, which
 * is the page's provenance trail and a published URL.
 */
export function PollersTab({ route }: PollersTabProps) {
  // Applied filters, not live form state: the legacy form only refetched on
  // Apply/Reset, and a select that refetched on change would fire a request per
  // keystroke of arrow-key browsing.
  const [filters, setFilters] = useState<PollerFilters>(DEFAULT_FILTERS);
  const [draft, setDraft] = useState<ActiveFilter>(DEFAULT_FILTERS.active);
  const [feedback, setFeedback] = useState<Feedback>({});

  const pollers = usePollers(filters);
  const summary = usePollersSummary();
  const force = useForcePoller();

  // Expire row messages a few seconds after the last one, rather than tying them
  // to the list refetch. Clearing on fresh data was the first attempt and it is
  // wrong twice over: the invalidation refetch lands almost immediately, so the
  // confirmation is never readable — which is the legacy tab's own flaw, since its
  // `refresh()` wiped the span as soon as it returned.
  //
  // One timer for the whole map, reset on each new message, so several rows in
  // flight need no per-key bookkeeping.
  useEffect(() => {
    if (Object.keys(feedback).length === 0) return;
    const timer = setTimeout(() => setFeedback({}), FEEDBACK_TTL_MS);
    return () => clearTimeout(timer);
  }, [feedback]);

  const setRowFeedback = (pollerId: string, message: string) =>
    setFeedback((prev) => ({ ...prev, [pollerId]: message }));

  const checkNow = async (pollerId: string) => {
    setRowFeedback(pollerId, CHECKING);
    try {
      const { ok, status, body } = await force.mutateAsync(pollerId);
      if (ok) {
        setRowFeedback(pollerId, QUEUED);
      } else if (status === COOLDOWN_STATUS) {
        const retry = (body as ForcePollerCooldown | null)?.retry_after_sec;
        setRowFeedback(pollerId, `try again in ${retry ?? '?'}s`);
      } else if (status === NOT_FOUND_STATUS) {
        // The row is stale rather than the press being wrong; the list
        // invalidation in useForcePoller is the whole response.
        setRowFeedback(pollerId, '');
      } else {
        setRowFeedback(pollerId, `error (${status})`);
      }
    } catch (err) {
      setRowFeedback(pollerId, `error: ${(err as Error)?.message ?? 'failed'}`);
    }
  };

  const rows = (pollers.data?.pollers ?? []).map((poller) => ({
    id: (
      <a
        href={`${route.hrefFor(TAB_RUNS)}&poller_id=${encodeURIComponent(String(poller.id))}`}
        onClick={(e) => {
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
          e.preventDefault();
          route.goToTab(TAB_RUNS, { poller_id: String(poller.id) });
        }}
      >
        {poller.id}
      </a>
    ),
    provider: poller.provider,
    parentRef: poller.parent_ref,
    status: <Tag status={poller.active ? 'success' : undefined}>{poller.active ? 'active' : 'dormant'}</Tag>,
    watches: poller.attached_watches,
    nextRun: formatTimestamp(poller.next_run_at),
    lastRun: poller.last_run_at ? formatTimestamp(poller.last_run_at) : NONE,
    claimed: poller.claimed_until ? formatTimestamp(poller.claimed_until) : NONE,
    actions: <CheckNowCell poller={poller} feedback={feedback[String(poller.id)]} onCheck={checkNow} />,
  }));

  return (
    <>
      <section className="rt-dash-panel">
        <h2>Status</h2>
        <div className="rt-dash-counters">
          {summary.isPending && 'Loading…'}
          {summary.isError && `Counters error: ${(summary.error as Error)?.message ?? 'failed'}`}
          {summary.data && (
            <>
              <Counter label="active" value={summary.data.active} />
              <Counter label="dormant" value={summary.data.dormant} />
              <Counter label="due now" value={summary.data.due_now} />
              <Counter label="claimed" value={summary.data.claimed} />
            </>
          )}
        </div>
      </section>

      <section className="rt-dash-panel">
        <h2>Filter</h2>
        <form
          className="rt-dash-filters"
          onSubmit={(e) => {
            e.preventDefault();
            setFilters({ active: draft });
          }}
          onReset={(e) => {
            e.preventDefault();
            setDraft(DEFAULT_FILTERS.active);
            setFilters(DEFAULT_FILTERS);
          }}
        >
          <Select
            id="pollers-active"
            name="active"
            label="Active"
            options={ACTIVE_OPTIONS}
            defaultValue={DEFAULT_FILTERS.active}
            onChange={(e) => setDraft((e.target as HTMLSelectElement).value as ActiveFilter)}
          />
          <div className="rt-dash-actions">
            <Button variant="primary" type="submit">
              Apply
            </Button>
            <Button variant="secondary" type="reset">
              Reset
            </Button>
          </div>
        </form>
      </section>

      <section className="rt-dash-panel" aria-live="polite">
        {pollers.isError ? (
          <Banner status="error">{`Error: ${(pollers.error as Error)?.message ?? 'failed'}`}</Banner>
        ) : pollers.isPending ? (
          <Skeleton aria-label="Loading pollers" />
        ) : (
          <>
            {/* The count line shows alongside the empty message, as it did before:
                "0 pollers." answers a different question from "No pollers." — one
                is the query result, the other is what to do about it. */}
            <div className="rt-dash-status">{`${pollers.data.total} poller${pollers.data.total === 1 ? '' : 's'}.`}</div>
            {rows.length === 0 ? <EmptyState title="No pollers." /> : <Table columns={COLUMNS} rows={rows} />}
          </>
        )}
      </section>
    </>
  );
}

function Counter({ label, value }: { label: string; value: number }) {
  return (
    <Tag>
      {label} <strong>{value}</strong>
    </Tag>
  );
}

interface CheckNowCellProps {
  poller: AvailabilityPoller;
  feedback: string | undefined;
  onCheck: (pollerId: string) => void;
}

/**
 * The per-row "check now" button and its feedback.
 *
 * Disabled for a dormant poller (nothing to bring forward) and while its own
 * message says it is in flight. Deliberately NOT disabled by the mutation's
 * global `isPending`: several rows can be checked at once, and one in-flight row
 * must not freeze the others.
 */
function CheckNowCell({ poller, feedback, onCheck }: CheckNowCellProps) {
  const pollerId = String(poller.id);
  return (
    <span className="rt-dash-row-actions">
      <Button
        size="sm"
        disabled={!poller.active || feedback === CHECKING}
        onClick={() => onCheck(pollerId)}
      >
        check now
      </Button>
      {feedback ? <span className="rt-dash-feedback">{feedback}</span> : null}
    </span>
  );
}
