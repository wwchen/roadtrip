// Availability dashboard reads plus the "check now" force-pull action.
import type {
  AvailabilityChangeSchema,
  AvailabilityPollersListResponse,
  AvailabilityPollerSchema,
  AvailabilityPollersSummary,
  AvailabilityRunSchema,
  AvailabilityRunsListResponse,
  AvailabilitySnapshotsSummaryResponse,
  AvailabilitySnapshotStatsSchema,
  CheckNowCooldownDto,
  CheckNowResponseDto,
  ListAvailabilityChangesResponse,
} from './generated/api-types';
import { jsonGetOk, jsonPost, type RequestOptions } from './http';

const POLLERS_URL = '/api/availability/pollers';
const POLLERS_SUMMARY_URL = '/api/availability/pollers/summary';
const RUNS_URL = '/api/availability/runs';
const CHANGES_URL = '/api/availability/changes';
const CHANGES_SUMMARY_URL = '/api/availability/changes/summary';

const FORCE_ACTION = 'force';
const RUNS_ACTION = 'runs';

export type AvailabilityPoller = AvailabilityPollerSchema;

export type PollersListResponse = AvailabilityPollersListResponse;

export type PollersSummary = AvailabilityPollersSummary;

export type AvailabilityRun = AvailabilityRunSchema;

export type RunsListResponse = AvailabilityRunsListResponse;

export type AvailabilityChange = AvailabilityChangeSchema;

export type ChangesListResponse = ListAvailabilityChangesResponse;

/** Per-date stats for one POI. */
export type SnapshotStats = AvailabilitySnapshotStatsSchema;

export type ChangesSummaryResponse = AvailabilitySnapshotsSummaryResponse;

/** The 200 body. */
export type ForcePollerAccepted = CheckNowResponseDto;

/** The 429 body. */
export type ForcePollerCooldown = CheckNowCooldownDto;

/** 200 accepted · 429 cooldown (body has retry_after_sec) · 404 poller gone. */
export interface ForcePollerResult {
  status: number;
  ok: boolean;
  /** `ForcePollerAccepted` on 200, `ForcePollerCooldown` on 429, error DTO otherwise. */
  body: ForcePollerAccepted | ForcePollerCooldown | Record<string, unknown> | null;
}

export interface ListPollersParams extends RequestOptions {
  /** Passed through as-is; the route parses the string form. */
  active?: boolean | string;
  limit?: number;
  offset?: number;
}

export interface ListRunsParams extends RequestOptions {
  status?: string;
  pollerId?: number | string;
  since?: string;
  limit?: number;
}

export interface ListChangesParams extends RequestOptions {
  targetDate?: string;
  limit?: number;
}

const pollerUrl = (pollerId: number | string, action: string): string =>
  `${POLLERS_URL}/${encodeURIComponent(String(pollerId))}/${action}`;

const withQuery = (url: string, qs: URLSearchParams): string =>
  qs.toString() ? `${url}?${qs}` : url;

export function listPollers({
  active,
  limit,
  offset,
  signal,
}: ListPollersParams = {}): Promise<PollersListResponse> {
  const qs = new URLSearchParams();
  if (active != null && active !== '') qs.set('active', String(active));
  if (limit != null) qs.set('limit', String(limit));
  if (offset != null) qs.set('offset', String(offset));
  return jsonGetOk<PollersListResponse>(withQuery(POLLERS_URL, qs), { signal });
}

export function getPollersSummary({ signal }: RequestOptions = {}): Promise<PollersSummary> {
  return jsonGetOk<PollersSummary>(POLLERS_SUMMARY_URL, { signal });
}

/**
 * Force a poller due now ("check now").
 *
 * Unlike the GET wrappers this does NOT throw on non-2xx — the caller inspects
 * `status` to distinguish 200 (accepted), 429 (cooldown active, body has
 * retry_after_sec), and 404 (poller gone). A cooldown is an expected answer to a
 * button press, not an error.
 */
export async function forcePoller(
  pollerId: number | string,
  { signal }: RequestOptions = {},
): Promise<ForcePollerResult> {
  const url = pollerUrl(pollerId, FORCE_ACTION);
  const response = await jsonPost(url, {}, { signal });
  const body = (await response.json().catch(() => null)) as ForcePollerResult['body'];
  return { status: response.status, ok: response.ok, body };
}

export function listRunsForPoller(
  pollerId: number | string,
  { limit, signal }: { limit?: number } & RequestOptions = {},
): Promise<RunsListResponse> {
  const qs = new URLSearchParams();
  if (limit != null) qs.set('limit', String(limit));
  return jsonGetOk<RunsListResponse>(withQuery(pollerUrl(pollerId, RUNS_ACTION), qs), { signal });
}

export function listRuns({
  status,
  pollerId,
  since,
  limit,
  signal,
}: ListRunsParams = {}): Promise<RunsListResponse> {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (pollerId != null && pollerId !== '') qs.set('poller_id', String(pollerId));
  if (since) qs.set('since', since);
  if (limit != null) qs.set('limit', String(limit));
  return jsonGetOk<RunsListResponse>(withQuery(RUNS_URL, qs), { signal });
}

// The changes endpoint is filtered by exactly one of campsite_id / poi_id. The
// two public wrappers differ only in which key they set, so they share one
// builder rather than repeating the query assembly.
function listChanges(
  key: 'campsite_id' | 'poi_id',
  id: number | string,
  { targetDate, limit, signal }: ListChangesParams,
): Promise<ChangesListResponse> {
  const qs = new URLSearchParams({ [key]: String(id) });
  if (targetDate) qs.set('target_date', targetDate);
  if (limit != null) qs.set('limit', String(limit));
  return jsonGetOk<ChangesListResponse>(`${CHANGES_URL}?${qs}`, { signal });
}

export function listChangesForCampsite(
  campsiteId: number | string,
  options: ListChangesParams = {},
): Promise<ChangesListResponse> {
  return listChanges('campsite_id', campsiteId, options);
}

export function listChangesForPoi(
  poiId: number | string,
  options: ListChangesParams = {},
): Promise<ChangesListResponse> {
  return listChanges('poi_id', poiId, options);
}

export function getChangesSummary(
  poiId: number | string,
  { dates, signal }: { dates?: string[] } & RequestOptions = {},
): Promise<ChangesSummaryResponse> {
  const qs = new URLSearchParams({ poi_id: String(poiId) });
  if (Array.isArray(dates) && dates.length > 0) qs.set('dates', dates.join(','));
  return jsonGetOk<ChangesSummaryResponse>(`${CHANGES_SUMMARY_URL}?${qs}`, { signal });
}
