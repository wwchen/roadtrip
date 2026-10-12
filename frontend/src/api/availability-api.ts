// Two reads. The week read returns the raw Response rather than parsed JSON,
// deliberately: the caller (the availability week grid) branches on status — 200
// with data, 200 with an "empty" body, and the provider-error shapes — and reads
// headers. The stay check is a stream of events, one per campground as it
// answers, read through `@/lib/event-stream`; a POI's failure rides inside its
// event, and only the request's own refusal is an HTTP one.
//
// Both are already decided: the backend fuses the campground's week, and sums a
// stay up per campground, so nothing here is re-derived client-side.
import type {
  AvailabilityCacheBlock,
  AvailabilityCellDto,
  AvailabilityDayDto,
  AvailabilityWatchCapabilitiesDto,
  BulkPoiSummaryDto,
  CampsiteKind,
  PoiCampsitesAvailabilityResponseDto,
  StayAnswerDto,
  StayCheckDoneDto,
} from './generated/api-types';
import { readEventStream } from '@/lib/event-stream';
import { CREDENTIALS, jsonPostStream, type RequestOptions } from './http';

const START_DATE_PARAM = 'start_date';
const END_DATE_PARAM = 'end_date';
const SITE_TYPE_PARAM = 'site_type';

export type { AddToCartState, AvailabilityWindowState, StayAvailabilityStatus } from './generated/api-types';

export type AvailabilityCell = AvailabilityCellDto;
export type AvailabilityDay = AvailabilityDayDto;
export type AvailabilityCache = AvailabilityCacheBlock;
export type WatchCapabilities = AvailabilityWatchCapabilitiesDto;
export type PoiCampsitesAvailabilityResponse = PoiCampsitesAvailabilityResponseDto;
export type StaySummary = BulkPoiSummaryDto;
export type StayAnswer = StayAnswerDto;

export const STAY_STREAM_URL = '/api/pois/availability/bulk/stream';

/** The stay stream's event names, as its contract row declares them (`API_ENDPOINTS`). */
export const STAY_ANSWER_EVENT = 'answer';
export const STAY_DONE_EVENT = 'done';

export interface StayStreamOptions extends RequestOptions {
  /** Called whenever the stream sends anything, keep-alives included: it is still alive. */
  onActivity?: () => void;
}

/** One event of the stay stream: a campground's answer, or the end of the check. */
export type StayStreamEvent = { kind: 'answer'; answer: StayAnswerDto } | { kind: 'done'; done: StayCheckDoneDto };

/**
 * The per-POI error codes that are a campground refusing the dates themselves —
 * past its booking horizon, or before its first bookable day — rather than a
 * failure to ask. The backend's `AvailabilityServiceError.BadDateWindow` codes.
 */
export const STAY_REFUSAL_CODES = ['beyond_booking_horizon', 'start_before_earliest'] as const;
export type StayRefusalCode = (typeof STAY_REFUSAL_CODES)[number];

export function isStayRefusal(code: string): code is StayRefusalCode {
  return (STAY_REFUSAL_CODES as readonly string[]).includes(code);
}

/**
 * The code a campground answers when it ran out of time — its own per-POI limit,
 * or the check's — rather than failing (`STAY_TIMEOUT_ERROR` in the backend's
 * `BulkAvailabilityController`). Unlike other failures it may well answer if asked again.
 */
export const STAY_TIMEOUT_CODE = 'timeout';

/**
 * The provider's season block rides the wire unparsed, so `reopens_on` is
 * narrowed here rather than asserted at the one call site that reads it.
 */
export function seasonReopensOn(season: unknown): string | undefined {
  if (!season || typeof season !== 'object') return undefined;
  const value = (season as Record<string, unknown>).reopens_on;
  return typeof value === 'string' ? value : undefined;
}

export interface PoiCampsitesAvailabilityParams extends RequestOptions {
  startDate?: string;
  endDate?: string;
  siteType?: string;
}

export function requestPoiCampsitesAvailability(
  poiId: number | string,
  { startDate, endDate, siteType, signal }: PoiCampsitesAvailabilityParams = {},
): Promise<Response> {
  const params = new URLSearchParams();
  if (startDate) params.set(START_DATE_PARAM, startDate);
  if (endDate) params.set(END_DATE_PARAM, endDate);
  if (siteType) params.set(SITE_TYPE_PARAM, siteType);
  const query = params.toString();
  const suffix = query ? `?${query}` : '';
  // Per-principal: the response's `watch_capabilities` depend on who is asking,
  // so the session cookie has to ride along like every other write in `http.ts`.
  return fetch(
    `/api/pois/${encodeURIComponent(String(poiId))}/campsites/availability${suffix}`,
    { credentials: CREDENTIALS, signal },
  );
}

export interface StayCheckRequest {
  poiIds: readonly number[];
  /** `YYYY-MM-DD`, the first night. */
  start: string;
  /** `YYYY-MM-DD`, the morning you leave: the stay is every night before it. */
  end: string;
  siteType: CampsiteKind | null;
  groupSize: number | null;
  /** Ask the providers again rather than answer from recent observations: a re-check. */
  fresh: boolean;
}

/**
 * How a stay looks at each of these campgrounds, as each answers: one `answer`
 * per POI — its summary or its error code — in the order they land, then `done`.
 * The POIs start in the order given, so pass the nearest first. A refused
 * request throws before any event: a throttled caller gets an `HttpError` whose
 * `code` is `ip_throttled`. An event this client does not know is skipped, so the
 * stream can grow one without breaking it.
 */
export async function* streamStays(
  { poiIds, start, end, siteType, groupSize, fresh }: StayCheckRequest,
  { signal, onActivity }: StayStreamOptions = {},
): AsyncGenerator<StayStreamEvent> {
  const body = await jsonPostStream(
    STAY_STREAM_URL,
    {
      poi_ids: poiIds,
      start_date: start,
      end_date: end,
      site_type: siteType ? [siteType] : [],
      ...(groupSize ? { group_size: groupSize } : {}),
      ...(fresh ? { fresh: true } : {}),
    },
    { signal },
  );
  for await (const event of readEventStream(body, { onActivity })) {
    if (event.event === STAY_ANSWER_EVENT) yield { kind: 'answer', answer: JSON.parse(event.data) as StayAnswerDto };
    else if (event.event === STAY_DONE_EVENT) yield { kind: 'done', done: JSON.parse(event.data) as StayCheckDoneDto };
  }
}
