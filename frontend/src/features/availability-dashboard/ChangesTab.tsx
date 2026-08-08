import { useState } from 'react';
import { Banner, Button, EmptyState, Skeleton, Table, TextField } from '@ui';
import type { SnapshotStats } from '@/api/availability-dashboard-api';
import {
  dayOfWeek,
  formatDuration,
  formatTimestamp,
  SECONDS_PER_DAY,
  SECONDS_PER_HOUR,
  SECONDS_PER_MINUTE,
} from '@/lib/format';
import { ChangesChart } from './ChangesChart';
import {
  hasOneChangeTarget,
  useChanges,
  useChangesSummary,
  type ChangeFilters,
} from './useDashboard';
import type { TabParams, TabRoute } from './useTabRoute';

const NONE = '—';
/** No observation has ever shown this date open. */
const NEVER_OPEN = '∞';

const EMPTY_FILTERS: ChangeFilters = { poiId: '', campsiteId: '', targetDate: '' };

const PROMPT = 'Set a Campsite ID or POI ID to load changes.';
const EXACTLY_ONE = 'Set exactly one of Campsite ID or POI ID.';

const CHANGE_COLUMNS = [
  { key: 'campsite', label: 'campsite' },
  { key: 'targetDate', label: 'target date' },
  { key: 'observed', label: 'observed' },
  { key: 'from', label: 'from' },
  { key: 'to', label: 'to' },
];

const STATS_COLUMNS = [
  { key: 'targetDate', label: 'target date' },
  { key: 'totalRuns', label: 'total runs' },
  { key: 'cadence', label: 'median cadence' },
  { key: 'firstRun', label: 'first run' },
  { key: 'lastRun', label: 'last run' },
  { key: 'lastAvailable', label: 'last available seen' },
  { key: 'minWindow', label: 'shortest avail window' },
  { key: 'maxWindow', label: 'longest avail window' },
];

function filtersFromParams(params: TabParams): ChangeFilters {
  return {
    poiId: params.poi_id ?? '',
    campsiteId: params.campsite_id ?? '',
    targetDate: params.target_date ?? '',
  };
}

/**
 * How long before the target date the last "available" reading was.
 *
 * Answers the operational question this dashboard exists for: how much warning
 * does a watch actually get? "3h before" is the useful reading; the absolute
 * timestamp is kept as the cell's title.
 */
export function lastAvailableLabel(stats: SnapshotStats): string {
  if (!stats.last_open_at) return NEVER_OPEN;
  const targetStart = Date.parse(`${stats.target_date}T00:00:00Z`);
  const deltaSec = Math.round((targetStart - Date.parse(stats.last_open_at)) / 1000);
  if (deltaSec <= 0) return 'on date';
  if (deltaSec < SECONDS_PER_HOUR) return `${Math.round(deltaSec / SECONDS_PER_MINUTE)}m before`;
  if (deltaSec < SECONDS_PER_DAY) return `${Math.round(deltaSec / SECONDS_PER_HOUR)}h before`;
  return `${Math.round(deltaSec / SECONDS_PER_DAY)}d before`;
}

export interface ChangesTabProps {
  route: TabRoute;
}

/**
 * Rebuild of web/components/availability/snapshots-tab.js.
 *
 * Scoped to exactly one campsite or one POI — the backend rejects neither and
 * both, so the form enforces it rather than surfacing a 400. Nothing is requested
 * until a target is set, which is why the panel opens on a prompt.
 */
export function ChangesTab({ route }: ChangesTabProps) {
  const seeded = filtersFromParams(route.params);
  const [filters, setFilters] = useState<ChangeFilters>(seeded);
  const [draft, setDraft] = useState<ChangeFilters>(seeded);

  const targeted = hasOneChangeTarget(filters);
  const changes = useChanges(filters);
  // POI only: a campsite-scoped view has no summary endpoint.
  const summary = useChangesSummary(targeted && filters.poiId ? filters.poiId : '');

  const apply = (next: ChangeFilters) => {
    setFilters(next);
    if (!hasOneChangeTarget(next)) return;
    // Mirror the applied filter into the URL so the view is linkable — the legacy
    // tab did this here too, and only for a valid target.
    route.setParams({
      ...(next.poiId ? { poi_id: next.poiId } : {}),
      ...(next.campsiteId ? { campsite_id: next.campsiteId } : {}),
      ...(next.targetDate ? { target_date: next.targetDate } : {}),
    });
  };

  const changeRows = (changes.data?.changes ?? []).map((change) => ({
    campsite: change.campsite_name || (change.campsite_id != null ? `#${change.campsite_id}` : NONE),
    targetDate: change.target_date,
    observed: formatTimestamp(change.observed_at),
    from: change.from_status || NONE,
    to: change.to_status,
  }));

  const statsRows = (summary.data?.stats ?? []).map((stats) => ({
    targetDate: (
      <>
        {stats.target_date} <span className="rt-dash-muted">{dayOfWeek(stats.target_date)}</span>
      </>
    ),
    totalRuns: stats.total_runs,
    cadence: stats.median_cadence_sec != null ? formatDuration(stats.median_cadence_sec) : NONE,
    firstRun: stats.first_run_at ? formatTimestamp(stats.first_run_at) : NONE,
    lastRun: stats.last_run_at ? formatTimestamp(stats.last_run_at) : NONE,
    lastAvailable: (
      <span title={stats.last_open_at ?? undefined}>{lastAvailableLabel(stats)}</span>
    ),
    minWindow: stats.min_open_window_sec != null ? formatDuration(stats.min_open_window_sec) : NONE,
    maxWindow: stats.max_open_window_sec != null ? formatDuration(stats.max_open_window_sec) : NONE,
  }));

  const changeCount = changes.data?.changes.length ?? 0;

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
            setDraft(EMPTY_FILTERS);
            apply(EMPTY_FILTERS);
          }}
        >
          {/* Keyed on the applied value so Reset clears the rendered inputs —
              LDS controls are uncontrolled, so reseeding means remounting. */}
          <TextField
            key={`poi:${filters.poiId}`}
            id="changes-poi-id"
            name="poi_id"
            label="POI ID"
            inputMode="numeric"
            defaultValue={filters.poiId}
            onChange={(e) => setDraft((d) => ({ ...d, poiId: (e.target as HTMLInputElement).value }))}
          />
          <TextField
            key={`campsite:${filters.campsiteId}`}
            id="changes-campsite-id"
            name="campsite_id"
            label="Campsite ID"
            inputMode="numeric"
            defaultValue={filters.campsiteId}
            onChange={(e) =>
              setDraft((d) => ({ ...d, campsiteId: (e.target as HTMLInputElement).value }))
            }
          />
          <TextField
            key={`date:${filters.targetDate}`}
            id="changes-target-date"
            name="target_date"
            label="Target Date"
            type="date"
            defaultValue={filters.targetDate}
            onChange={(e) =>
              setDraft((d) => ({ ...d, targetDate: (e.target as HTMLInputElement).value }))
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

      {statsRows.length > 0 && (
        <section className="rt-dash-panel">
          <h2>Stats</h2>
          <Table columns={STATS_COLUMNS} rows={statsRows} />
        </section>
      )}

      {changeCount > 0 && (
        <section className="rt-dash-panel">
          <h2>Timeline</h2>
          <ChangesChart changes={changes.data!.changes} />
        </section>
      )}

      <section className="rt-dash-panel" aria-live="polite">
        {!targeted ? (
          // Neither set is the opening state; both set is a mistake worth naming.
          <div className="rt-dash-status">
            {!filters.poiId && !filters.campsiteId ? PROMPT : EXACTLY_ONE}
          </div>
        ) : changes.isError ? (
          <Banner status="error">{`Error: ${(changes.error as Error)?.message ?? 'failed'}`}</Banner>
        ) : changes.isPending ? (
          <Skeleton aria-label="Loading changes" />
        ) : (
          <>
            <div className="rt-dash-status">{`${changeCount} change${changeCount === 1 ? '' : 's'}.`}</div>
            {changeCount === 0 ? (
              <EmptyState title="No changes." />
            ) : (
              <Table columns={CHANGE_COLUMNS} rows={changeRows} />
            )}
          </>
        )}
      </section>
    </>
  );
}
