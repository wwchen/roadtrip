import type { Db } from '@roadtrip/db';
import type { AvailabilityEgressRequestDto } from '../../contract';
import { egressRequest, targetWindow, weekTarget, type EgressFetch, type WeekSettings } from './availabilityMatrixService';

const REFRESH_LOG = 'availability refresh';
const UNCLASSIFIED_FAILURE = 'unclassified';
const REFRESHED = 'refreshed';
const FAILED = 'failed';

export type RefreshPlan =
  | { kind: 'nothing' }
  | { kind: 'disabled' }
  | { kind: 'unsupported' }
  | { kind: 'fetch'; request: AvailabilityEgressRequestDto };

export type FetchPlan = Extract<RefreshPlan, { kind: 'fetch' }>;

export type RefreshOutcome = { cells: number } | { failed: string };

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
  if (target.campsites.length === 0) return { kind: 'nothing' };
  return { kind: 'fetch', request: egressRequest(target, window, 'refresh') };
}

export async function runRefresh(plan: FetchPlan, fetch: EgressFetch): Promise<RefreshOutcome> {
  const result = await fetch(plan.request);
  if (result.kind === 'error') {
    const failed = result.code ?? UNCLASSIFIED_FAILURE;
    console.warn(REFRESH_LOG, JSON.stringify({ outcome: FAILED, status: result.status, code: failed, campground: plan.request.campground }));
    return { failed };
  }
  console.info(REFRESH_LOG, JSON.stringify({ outcome: REFRESHED, cells: result.body.cells.length, campground: plan.request.campground }));
  return { cells: result.body.cells.length };
}
