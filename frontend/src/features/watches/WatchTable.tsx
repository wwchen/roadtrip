import { useMemo, useState, type ReactNode } from 'react';
import { Button, EmptyState, Table, Tag } from '@ui';
import type { Watch, WatchStatus } from '@/api/watches-api';
import {
  TRIGGER_KIND_ATC,
  TRIGGER_KIND_EMAIL_NOTIFY,
  TRIGGER_KIND_SLACK_NOTIFY,
} from '@/lib/watch-triggers';

export interface WatchTableProps {
  watches: Watch[];
  poiNames: Map<number, string>;
  onEdit: (id: number) => void;
  onSetStatus: (id: number, status: WatchStatus) => void;
  onDelete: (id: number) => void;
  busy?: boolean;
}

type SortKey = 'id' | 'poi' | 'start_date' | 'status' | 'last_run_at';
type SortDir = 'asc' | 'desc';

/** Matches the legacy data-table's defaultSort. */
const DEFAULT_SORT: { key: SortKey; dir: SortDir } = { key: 'id', dir: 'desc' };

const STATUS_LABELS: Record<WatchStatus, string> = {
  active: 'Active',
  paused: 'Paused',
  done: 'Done',
};

/** Status → LDS Tag hue. Replaces the legacy per-row `is-paused`/`is-done` tint. */
const STATUS_HUE: Record<WatchStatus, 'green' | 'yellow' | 'gray'> = {
  active: 'green',
  paused: 'yellow',
  done: 'gray',
};

const RELATIVE_MINUTE_S = 60;
const RELATIVE_HOUR_MIN = 60;
const RELATIVE_DAY_H = 24;

/** An em dash in the muted role, for every empty cell. */
const Blank = () => <span className="rt-watch-cell-blank">—</span>;

/**
 * Wall-clock age of an ISO instant. Ported from the legacy `relativeTime`.
 *
 * Reads the clock at render time, so a row's "5m ago" only refreshes when the
 * table re-renders — same as the original, which re-rendered on every reload.
 */
export function relativeTime(iso: string, now: number = Date.now()): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const secs = Math.max(0, Math.round((now - then) / 1000));
  if (secs < RELATIVE_MINUTE_S) return 'just now';
  const mins = Math.round(secs / RELATIVE_MINUTE_S);
  if (mins < RELATIVE_HOUR_MIN) return `${mins}m ago`;
  const hrs = Math.round(mins / RELATIVE_HOUR_MIN);
  if (hrs < RELATIVE_DAY_H) return `${hrs}h ago`;
  return `${Math.round(hrs / RELATIVE_DAY_H)}d ago`;
}

/**
 * A watch's start date as `Mon D`.
 *
 * Formatted in UTC on purpose: the value is a calendar date, so rendering it in
 * the viewer's zone would shift it a day west of UTC.
 */
export function formatWatchDate(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const d = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' });
}

/** The label a watch shows when it has no POI target. Ported from the original. */
export function watchFallbackName(watch: Watch): string {
  const site = watch.campsite;
  const name = typeof site?.name === 'string' ? site.name : '';
  if (name) {
    const loop = typeof site?.loop_name === 'string' ? site.loop_name : '';
    return loop ? `${loop} / ${name}` : name;
  }
  return `Watch #${watch.id}`;
}

/**
 * Order two rows, direction included.
 *
 * The direction is applied inside rather than by multiplying the result,
 * because the blanks-last rule must NOT flip with it: an unchecked watch is not
 * "the oldest" when the column is sorted descending.
 */
function compare(a: Watch, b: Watch, key: SortKey, dir: SortDir): number {
  const sign = dir === 'asc' ? 1 : -1;
  // These two are always populated, so there is no blank case to protect.
  if (key === 'id') return (a.id - b.id) * sign;
  if (key === 'poi') return ((a.poi_id ?? 0) - (b.poi_id ?? 0)) * sign;

  const av = String((key === 'status' ? a.status : a[key]) ?? '');
  const bv = String((key === 'status' ? b.status : b[key]) ?? '');
  if (av === bv) return 0;
  if (!av) return 1;
  if (!bv) return -1;
  return (av < bv ? -1 : 1) * sign;
}

/**
 * Rebuild of web/watches/watch-table.js on LDS.
 *
 * LDS's Table is presentational — no sort hooks, no per-row class — so sorting
 * lives here (where it is testable without the DOM) and the row-level
 * paused/done tint is replaced by a hued status Tag.
 */
export function WatchTable({
  watches,
  poiNames,
  onEdit,
  onSetStatus,
  onDelete,
  busy = false,
}: WatchTableProps) {
  const [sort, setSort] = useState(DEFAULT_SORT);
  /** The row whose delete button is armed — the double-confirm step. */
  const [armedDeleteId, setArmedDeleteId] = useState<number | null>(null);

  const sorted = useMemo(
    () => watches.slice().sort((a, b) => compare(a, b, sort.key, sort.dir)),
    [watches, sort],
  );

  const toggleSort = (key: SortKey) =>
    setSort((s) => ({ key, dir: s.key === key && s.dir === 'desc' ? 'asc' : 'desc' }));

  const sortHeader = (key: SortKey, label: string): ReactNode => (
    <button
      type="button"
      className="rt-watch-table-sort"
      aria-label={`Sort by ${label}`}
      onClick={() => toggleSort(key)}
    >
      {label}
      {sort.key === key && <span aria-hidden="true">{sort.dir === 'asc' ? ' ↑' : ' ↓'}</span>}
    </button>
  );

  if (watches.length === 0) {
    return <EmptyState title="No watches yet" body="Create one above to be told when a site opens." />;
  }

  const columns = [
    { key: 'id', label: sortHeader('id', 'ID') },
    { key: 'poi', label: sortHeader('poi', 'POI') },
    { key: 'start_date', label: sortHeader('start_date', 'Date') },
    { key: 'trigger', label: 'Trigger' },
    { key: 'status', label: sortHeader('status', 'Status') },
    { key: 'last_run_at', label: sortHeader('last_run_at', 'Last checked') },
    { key: 'actions', label: '' },
  ];

  const rows = sorted.map((watch) => ({
    id: watch.id,
    poi: <PoiCell watch={watch} poiNames={poiNames} />,
    start_date: formatWatchDate(watch.start_date) ?? <Blank />,
    trigger: <TriggerCell watch={watch} />,
    status: (
      <Tag hue={STATUS_HUE[watch.status] ?? 'gray'} size="sm">
        {STATUS_LABELS[watch.status] ?? watch.status}
      </Tag>
    ),
    last_run_at: <LastCheckedCell watch={watch} />,
    actions: (
      <span className="rt-watch-table-actions">
        <Button
          size="sm"
          variant="tertiary"
          disabled={busy}
          aria-label={watch.status === 'active' ? 'Pause' : 'Resume'}
          onClick={() => onSetStatus(watch.id, watch.status === 'active' ? 'paused' : 'active')}
        >
          {watch.status === 'active' ? 'Pause' : 'Resume'}
        </Button>
        <Button
          size="sm"
          variant="tertiary"
          disabled={busy}
          aria-label="Edit"
          onClick={() => onEdit(watch.id)}
        >
          Edit
        </Button>
        {/* Double-confirm, as in the original: the first click arms, the second
            deletes. Delete is irreversible and sits next to Pause. */}
        <Button
          size="sm"
          variant="tertiary"
          hue="red"
          armed={armedDeleteId === watch.id}
          disabled={busy}
          aria-label={armedDeleteId === watch.id ? 'Confirm delete' : 'Delete'}
          onClick={() => {
            if (armedDeleteId === watch.id) {
              setArmedDeleteId(null);
              onDelete(watch.id);
            } else {
              setArmedDeleteId(watch.id);
            }
          }}
        >
          {armedDeleteId === watch.id ? 'Delete?' : 'Delete'}
        </Button>
      </span>
    ),
  }));

  return (
    <div className="rt-watch-table-wrap">
      <Table columns={columns} rows={rows} />
    </div>
  );
}

function PoiCell({ watch, poiNames }: { watch: Watch; poiNames: Map<number, string> }) {
  const id = watch.poi_id;
  if (id == null) return <>{watchFallbackName(watch)}</>;
  return (
    <a className="rt-watch-table-poi" href={`/?poi=${encodeURIComponent(String(id))}`}>
      {poiNames.get(id) ?? `POI ${id}`}
    </a>
  );
}

function TriggerCell({ watch }: { watch: Watch }) {
  const kinds = Array.isArray(watch.trigger_kinds) ? watch.trigger_kinds : [];
  if (kinds.length === 0) return <Blank />;
  const labels: string[] = [];
  if (kinds.includes(TRIGGER_KIND_SLACK_NOTIFY)) labels.push('Slack');
  if (kinds.includes(TRIGGER_KIND_EMAIL_NOTIFY)) labels.push('Email');
  if (kinds.includes(TRIGGER_KIND_ATC)) labels.push('Cart');
  return (
    <span className="rt-watch-table-trigger">
      {labels.map((label) => (
        <Tag key={label} size="sm" emphasis="subtle">
          {label}
        </Tag>
      ))}
    </span>
  );
}

function LastCheckedCell({ watch }: { watch: Watch }) {
  if (watch.last_run_status === 'failed') {
    return (
      <Tag hue="red" size="sm" title={watch.last_run_error ?? undefined}>
        error
      </Tag>
    );
  }
  const at = watch.last_run_at;
  if (!at) return <Blank />;
  return <span title={at}>{relativeTime(at)}</span>;
}
