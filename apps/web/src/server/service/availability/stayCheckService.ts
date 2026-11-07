import type { Db } from '@roadtrip/db';
import type { CampsiteKind } from '@roadtrip/domain';
import { EGRESS_NOT_JSON_CODE, EGRESS_REDIRECTED_CODE, EGRESS_TIMEOUT_CODE, EGRESS_UNREACHABLE_CODE } from '../../client/availabilityEgress';
import type { StayAnswerDto, StayCheckOutcome, StayFailureReason, StayOutcome } from '../../contract';
import { toContractId } from '../../contractIds';
import { providerRefKey } from '../../repo/availabilityReadRepo';
import { AvailabilityTargetError, egressRequest, weekTarget, type EgressFetch } from './availabilityMatrixService';
import { fittingGroup, staySummary } from './staySummary';

const STAY_LOG = 'stay check';
const MS_PER_SECOND = 1000;

export interface StayCheckRequest {
  poiIds: bigint[];
  startDate: string;
  endDate: string;
  siteTypes: CampsiteKind[];
  groupSize: number | null;
  maxAgeSeconds: number;
}

export interface StayCheckLimits {
  fanOut: number;
  perPoiTimeoutMs: number;
  streamTimeoutMs: number;
}

export type StayEvent = { event: 'answer'; data: StayAnswerDto } | { event: 'done'; data: { outcome: StayCheckOutcome } };

interface StayFailure {
  outcome: StayOutcome;
  reason?: StayFailureReason;
  retryable?: boolean;
}

const refused = (reason: StayFailureReason): StayFailure => ({ outcome: 'refused', reason });
const retryable = (reason: StayFailureReason): StayFailure => ({ outcome: 'failed', reason, retryable: true });
const terminal = (reason: StayFailureReason): StayFailure => ({ outcome: 'failed', reason, retryable: false });
const TIMED_OUT: StayFailure = { outcome: 'timed_out' };

const FAILURE_BY_CODE: Readonly<Record<string, StayFailure>> = {
  start_before_earliest: refused('start_before_earliest'),
  beyond_booking_horizon: refused('beyond_booking_horizon'),
  window_too_long: refused('window_too_long'),
  rate_limited: retryable('rate_limited'),
  upstream_blocked: retryable('upstream_blocked'),
  upstream_5xx: retryable('upstream_5xx'),
  upstream_unreachable: retryable('upstream_unreachable'),
  provider_misconfigured: terminal('provider_misconfigured'),
  unsupported: terminal('unsupported'),
  not_found: terminal('not_found'),
  unknown_campground: terminal('unknown_campground'),
  [EGRESS_TIMEOUT_CODE]: TIMED_OUT,
  [EGRESS_UNREACHABLE_CODE]: retryable('upstream_unreachable'),
  [EGRESS_REDIRECTED_CODE]: terminal('provider_misconfigured'),
  [EGRESS_NOT_JSON_CODE]: terminal('provider_misconfigured'),
};

export function stayFailureOf(code: string | null): StayFailure {
  return (code === null ? undefined : FAILURE_BY_CODE[code]) ?? terminal('unknown');
}

function failedAnswer(poiId: bigint, failure: StayFailure): StayAnswerDto {
  return { poi_id: toContractId(poiId), ...failure };
}

export async function answerStay(
  db: Db,
  poiId: bigint,
  request: StayCheckRequest,
  egress: EgressFetch | null,
  now: Date,
): Promise<StayAnswerDto> {
  if (egress === null) return failedAnswer(poiId, terminal('provider_misconfigured'));
  let target;
  try {
    target = await weekTarget(db, poiId, request.siteTypes);
  } catch (error) {
    if (error instanceof AvailabilityTargetError) return failedAnswer(poiId, terminal(error.code));
    throw error;
  }
  const freshAtOrAfter = new Date(now.getTime() - request.maxAgeSeconds * MS_PER_SECOND).toISOString();
  const result = await egress({
    ...egressRequest(target, request.startDate, request.endDate, 'refresh'),
    fresh_at_or_after: freshAtOrAfter,
  });
  if (result.kind === 'error') {
    console.warn(STAY_LOG, JSON.stringify({ poi: String(poiId), status: result.status, code: result.code }));
    return failedAnswer(poiId, stayFailureOf(result.code));
  }
  const known = new Set(result.body.campsites.map((campsite) => providerRefKey(campsite.data_provider, campsite.data_provider_ref)));
  const checked = fittingGroup(
    target.campsites.filter((campsite) => known.has(campsite.key)),
    request.groupSize,
  );
  return {
    poi_id: toContractId(poiId),
    outcome: 'answered',
    stay: staySummary(checked, result.body.cells, request.startDate, request.endDate, result.body.from_cache ?? false),
  };
}

function timeout<T>(ms: number, value: T): { promise: Promise<T>; cancel: () => void } {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const promise = new Promise<T>((resolve) => {
    timer = setTimeout(() => resolve(value), ms);
  });
  return { promise, cancel: () => clearTimeout(timer) };
}

export async function runStayCheck(
  poiIds: readonly bigint[],
  answer: (poiId: bigint) => Promise<StayAnswerDto>,
  limits: StayCheckLimits,
  send: (event: StayEvent) => void,
): Promise<void> {
  const answered = new Set<number>();
  let next = 0;
  let closed = false;
  const emit = (index: number, dto: StayAnswerDto) => {
    if (closed || answered.has(index)) return;
    answered.add(index);
    send({ event: 'answer', data: dto });
  };
  const worker = async () => {
    while (!closed && next < poiIds.length) {
      const index = next++;
      const poiId = poiIds[index]!;
      const perPoi = timeout(limits.perPoiTimeoutMs, failedAnswer(poiId, TIMED_OUT));
      const dto = await Promise.race([
        answer(poiId).catch((error: unknown) => {
          console.warn(STAY_LOG, JSON.stringify({ poi: String(poiId), error: String(error) }));
          return failedAnswer(poiId, terminal('unknown'));
        }),
        perPoi.promise,
      ]);
      perPoi.cancel();
      emit(index, dto);
    }
  };
  const workers = Promise.all(Array.from({ length: Math.min(limits.fanOut, poiIds.length) }, worker));
  const deadline = timeout(limits.streamTimeoutMs, 'deadline' as const);
  const finished = await Promise.race([workers.then(() => 'complete' as const), deadline.promise]);
  deadline.cancel();
  if (finished === 'deadline') {
    poiIds.forEach((poiId, index) => emit(index, failedAnswer(poiId, TIMED_OUT)));
  }
  closed = true;
  send({ event: 'done', data: { outcome: finished === 'deadline' ? 'deadline_reached' : 'complete' } });
}
