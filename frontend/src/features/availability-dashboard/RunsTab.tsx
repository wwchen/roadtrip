import { useState } from 'react';
import { Banner, Button, EmptyState, Select, Skeleton, Table, Tag, TextField } from '@ui';
import { formatTimestamp, truncate } from '@/lib/format';
import { useRuns, type RunFilters } from './useDashboard';
import { TAB_POLLERS, type TabParams, type TabRoute } from './useTabRoute';

const STATUS_OPTIONS = [
  { value: '', label: 'any' },
  { value: 'started', label: 'started' },
  { value: 'completed', label: 'completed' },
  { value: 'failed', label: 'failed' },
];

/** Status → Tag hue. An unrecognised status renders as a plain tag. */
const STATUS_TONE: Record<string, 'success' | 'error' | 'info'> = {
  completed: 'success',
  failed: 'error',
  started: 'info',
};

/** Matches the legacy cell width for a run's error text. */
const ERROR_MAX_CHARS = 80;

const NONE = '—';

const COLUMNS = [
  { key: 'id', label: 'id' },
  { key: 'poller', label: 'poller' },
  { key: 'status', label: 'status' },
  { key: 'snapshots', label: 'snapshots' },
  { key: 'duration', label: 'duration' },
  { key: 'started', label: 'started' },
  { key: 'error', label: 'error' },
];

export interface RunsTabProps {
  route: TabRoute;
}

/** The filters a URL can seed, e.g. from a poller row's link. */
function filtersFromParams(params: TabParams): RunFilters {
  return { status: params.status ?? '', pollerId: params.poller_id ?? '' };
}

/**
 * Rebuild of web/components/availability/runs-tab.js.
 *
 * Recent executions across all pollers, or filtered to one. Seeded from the URL
 * so a poller row's link lands here already scoped.
 */
export function RunsTab({ route }: RunsTabProps) {
  const seeded = filtersFromParams(route.params);
  const [filters, setFilters] = useState<RunFilters>(seeded);
  const [draft, setDraft] = useState<RunFilters>(seeded);

  const runs = useRuns(filters);

  const apply = (next: RunFilters) => {
    setFilters(next);
    // Keep the URL describing what is shown, so the filtered view is linkable.
    route.setParams({
      ...(next.status ? { status: next.status } : {}),
      ...(next.pollerId ? { poller_id: next.pollerId } : {}),
    });
  };

  const rows = (runs.data?.runs ?? []).map((run) => ({
    id: run.id,
    poller: (
      <a
        href={route.hrefFor(TAB_POLLERS)}
        onClick={(e) => {
          if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
          e.preventDefault();
          // Carries no params, as the original did: this goes to the poller LIST,
          // which has no per-poller filter to receive an id.
          route.goToTab(TAB_POLLERS);
        }}
      >
        {`#${run.poller_id}`}
      </a>
    ),
    status: <Tag status={STATUS_TONE[run.status]}>{run.status}</Tag>,
    snapshots: run.snapshot_count,
    duration: run.duration_ms != null ? `${run.duration_ms}ms` : NONE,
    started: formatTimestamp(run.started_at),
    error: run.error ? truncate(run.error, ERROR_MAX_CHARS) : '',
  }));

  return (
    <>
      <section className="rt-dash-panel">
        <h2>Filter</h2>
        <form
          className="rt-dash-filters"
          onSubmit={(e) => {
            e.preventDefault();
            apply(draft);
          }}
          onReset={(e) => {
            e.preventDefault();
            const cleared: RunFilters = { status: '', pollerId: '' };
            setDraft(cleared);
            apply(cleared);
          }}
        >
          {/* Keyed on the applied filters so Reset actually clears the rendered
              controls: LDS inputs are uncontrolled, so a remount is the reseed. */}
          <Select
            key={`status:${filters.status}`}
            id="runs-status"
            name="status"
            label="Status"
            options={STATUS_OPTIONS}
            defaultValue={filters.status}
            onChange={(e) =>
              setDraft((d) => ({ ...d, status: (e.target as HTMLSelectElement).value }))
            }
          />
          <TextField
            key={`poller:${filters.pollerId}`}
            id="runs-poller-id"
            name="poller_id"
            label="Poller ID"
            inputMode="numeric"
            defaultValue={filters.pollerId}
            onChange={(e) =>
              setDraft((d) => ({ ...d, pollerId: (e.target as HTMLInputElement).value }))
            }
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
        {runs.isError ? (
          <Banner status="error">{`Error: ${(runs.error as Error)?.message ?? 'failed'}`}</Banner>
        ) : runs.isPending ? (
          <Skeleton aria-label="Loading runs" />
        ) : (
          <>
            {/* Counts the rows returned, not a `total` — the runs response has no
                total field, and the legacy status line used `runs.length` too. */}
            <div className="rt-dash-status">{`${rows.length} run${rows.length === 1 ? '' : 's'}.`}</div>
            {rows.length === 0 ? <EmptyState title="No runs." /> : <Table columns={COLUMNS} rows={rows} />}
          </>
        )}
      </section>
    </>
  );
}
