import type { Db } from '@roadtrip/db';
import type { EgressResult } from '../../client/availabilityEgress';
import type { AvailabilityEgressRequestDto } from '../../contract';
import { campsiteIdsByProviderRef, currentCells, providerRefKey } from '../../repo/availabilityReadRepo';
import { recordObservations, type Observation } from '../../repo/availabilityWriteRepo';
import { targetWindow, weekTarget, type WeekSettings } from './availabilityMatrixService';
import { datesOf, isWindowFresh, weekCache } from './matrixFusion';

const REFRESH_LOG = 'availability refresh';
const UNCLASSIFIED_FAILURE = 'unclassified';
const UNANSWERED_STATUS = 'unknown';

export type RefreshPlan =
  | { kind: 'fresh' }
  | { kind: 'disabled' }
  | { kind: 'unsupported' }
  | { kind: 'fetch'; request: AvailabilityEgressRequestDto; campgroundId: bigint };

export type FetchPlan = Extract<RefreshPlan, { kind: 'fetch' }>;

export type RefreshOutcome = { written: number; dropped: number; filled: number } | { failed: string };

export interface RefreshDeps {
  db: Db;
  fetch: (request: AvailabilityEgressRequestDto) => Promise<EgressResult>;
}

export async function planRefresh(
  db: Db,
  poiId: bigint,
  startDate: string | null,
  endDate: string | null,
  settings: WeekSettings,
  refreshEnabled: boolean,
): Promise<RefreshPlan> {
  if (!refreshEnabled) return { kind: 'disabled' };
  const target = await weekTarget(db, poiId, [], settings);
  if (target.provider === null) return { kind: 'unsupported' };
  const window = targetWindow(target, startDate, endDate);
  if (target.campsiteIds.length === 0) return { kind: 'fresh' };
  const cells = await currentCells(db, target.campsiteIds, window.startDate, window.endDate);
  if (isWindowFresh(weekCache(target.campsiteIds, cells, window.startDate, window.endDate, settings.ttlSeconds, settings.now))) {
    return { kind: 'fresh' };
  }
  return {
    kind: 'fetch',
    campgroundId: target.campground.campgroundId,
    request: {
      campground: { data_provider: target.campground.dataProvider, data_provider_ref: target.campground.dataProviderRef },
      start_date: window.startDate,
      end_date: window.endDate,
    },
  };
}

export async function runRefresh(plan: FetchPlan, deps: RefreshDeps): Promise<RefreshOutcome> {
  const result = await deps.fetch(plan.request);
  if (result.kind === 'error') {
    const failed = result.code ?? UNCLASSIFIED_FAILURE;
    console.warn(REFRESH_LOG, JSON.stringify({ outcome: 'failed', status: result.status, code: failed, campground: plan.request.campground }));
    return { failed };
  }
  const ids = await campsiteIdsByProviderRef(deps.db, plan.campgroundId);
  const observations: Observation[] = [];
  let dropped = 0;
  for (const cell of result.body.cells) {
    const campsiteId = ids.get(providerRefKey(cell.campsite.data_provider, cell.campsite.data_provider_ref));
    if (campsiteId === undefined) {
      dropped += 1;
      continue;
    }
    observations.push({ campsiteId, date: cell.date, status: cell.status, observedAt: new Date(cell.observed_at) });
  }
  const filled = unansweredCells(observations, [...ids.values()], result.body.start_date, result.body.end_date, new Date());
  await recordObservations(deps.db, [...observations, ...filled]);
  const outcome = { written: observations.length, dropped, filled: filled.length };
  const line = JSON.stringify({ outcome: 'written', ...outcome, campground: plan.request.campground });
  if (dropped > 0) console.warn(REFRESH_LOG, line);
  else console.info(REFRESH_LOG, line);
  return outcome;
}

function unansweredCells(answered: readonly Observation[], campsiteIds: readonly bigint[], startDate: string, endDate: string, now: Date): Observation[] {
  const seen = new Set(answered.map((observation) => `${observation.campsiteId}|${observation.date}`));
  const observedAt = answered.length === 0 ? now : new Date(Math.min(...answered.map((observation) => observation.observedAt.getTime())));
  return campsiteIds.flatMap((campsiteId) =>
    datesOf(startDate, endDate)
      .filter((date) => !seen.has(`${campsiteId}|${date}`))
      .map((date) => ({ campsiteId, date, status: UNANSWERED_STATUS as Observation['status'], observedAt })),
  );
}
