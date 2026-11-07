import type { Db } from '@roadtrip/db';
import type { AvailabilityEgressRequestDto } from '../../contract';
import { AvailabilityWindowRefusedError, egressRequest, weekTarget, type EgressFetch } from './availabilityMatrixService';

const REFRESH_LOG = 'availability refresh';
const UNCLASSIFIED_FAILURE = 'unclassified';
const REFRESHED = 'refreshed';
const FAILED = 'failed';
const STATUS_BAD_REQUEST = 400;

export type RefreshPlan = { kind: 'nothing' } | { kind: 'disabled' } | { kind: 'fetch'; request: AvailabilityEgressRequestDto };

export type FetchPlan = Extract<RefreshPlan, { kind: 'fetch' }>;

export type RefreshOutcome = { cells: number } | { failed: string };

export async function planRefresh(
  db: Db,
  poiId: bigint,
  startDate: string | null,
  endDate: string | null,
  checkWindow: EgressFetch | null,
): Promise<RefreshPlan> {
  if (checkWindow === null) return { kind: 'disabled' };
  const target = await weekTarget(db, poiId, []);
  if (target.campsites.length === 0) return { kind: 'nothing' };
  const check = await checkWindow(egressRequest(target, startDate, endDate, 'stored'));
  if (check.kind === 'error' && check.status === STATUS_BAD_REQUEST && check.body !== null) throw new AvailabilityWindowRefusedError(check.body);
  return { kind: 'fetch', request: egressRequest(target, startDate, endDate, 'refresh') };
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
