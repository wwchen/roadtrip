// Watches are user intent for availability polling; the backend persists them
// and a scheduler turns them into polling jobs.
//
// Note the envelopes: the list route answers `{ total, limit, offset, watches }`
// and the single-watch routes answer `{ watch, watch_capabilities? }`.
import type {
  AvailabilityWatchCreateRequest,
  AvailabilityWatchListResponse,
  AvailabilityWatchResponse,
  AvailabilityWatchSchema,
  AvailabilityWatchTargetSchema,
  AvailabilityWatchUpdateRequest,
  WatchStatus,
} from './generated/api-types';
import { HttpError, jsonGetOk, type RequestOptions } from './http';

const BASE = '/api/watches';
const MODIFY_ACTION = 'modify';
const DELETE_ACTION = 'delete';
/** Pinned against WatchPageLinks.kt. The email builds the URL, this parses it. */
export const MAGIC_LINK_TOKEN_PARAM = 't';
export const MAGIC_LINK_WATCH_PARAM = 'watch';
export const MAGIC_LINK_ACTION_PARAM = 'action';
export const MAGIC_LINK_STOP_ACTION = 'stop';

export type { WatchStatus } from './generated/api-types';

/**
 * What a watch is pointed at. A watch has one or more targets; `poi_id` watches
 * a whole POI, `campsite_id` a single site.
 */
export type WatchTarget = AvailabilityWatchTargetSchema;

export type Watch = AvailabilityWatchSchema;

/** The GET /api/watches envelope. */
export type WatchListResponse = AvailabilityWatchListResponse;

/** The single-watch envelope. */
export type WatchResponse = AvailabilityWatchResponse;

export type CreateWatchRequest = AvailabilityWatchCreateRequest;

/** Every field is a partial update. */
export type UpdateWatchRequest = AvailabilityWatchUpdateRequest;

export interface ListWatchesParams extends RequestOptions {
  status?: WatchStatus;
  poiId?: number | string;
  campsiteId?: number | string;
  limit?: number;
  offset?: number;
}

/** Single-watch calls, which may be authorized by a link rather than a session. */
export interface WatchRequestOptions extends RequestOptions {
  magicLinkToken?: string | null;
}

function watchUrl(id: number | string, action?: string, magicLinkToken?: string | null): string {
  const path = `${BASE}/${encodeURIComponent(String(id))}`;
  const base = action ? `${path}/${action}` : path;
  if (!magicLinkToken) return base;
  return `${base}?${MAGIC_LINK_TOKEN_PARAM}=${encodeURIComponent(magicLinkToken)}`;
}

export function listWatches({
  status,
  poiId,
  campsiteId,
  limit,
  offset,
  signal,
}: ListWatchesParams = {}): Promise<WatchListResponse> {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (poiId != null && poiId !== '') qs.set('poi_id', String(poiId));
  if (campsiteId != null && campsiteId !== '') qs.set('campsite_id', String(campsiteId));
  if (limit != null) qs.set('limit', String(limit));
  if (offset != null) qs.set('offset', String(offset));
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk<WatchListResponse>(`${BASE}${suffix}`, { signal });
}

export function getWatch(
  id: number | string,
  { signal, magicLinkToken }: WatchRequestOptions = {},
): Promise<WatchResponse> {
  return jsonGetOk<WatchResponse>(watchUrl(id, undefined, magicLinkToken), { signal });
}

/**
 * Create a watch. Answers 201.
 *
 * Uses bare `fetch` with no explicit `credentials`, relying on the same-origin
 * default — preserved from the original rather than routed through http.ts,
 * because this path also attaches the raw response text to the error as `.body`
 * (the create form surfaces the backend's validation detail verbatim).
 */
export async function createWatch(
  body: CreateWatchRequest,
  { signal }: RequestOptions = {},
): Promise<WatchResponse> {
  const r = await fetch(BASE, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
  if (!r.ok) {
    const text = await r.text().catch(() => '');
    const err = new HttpError(BASE, r.status);
    err.body = text;
    throw err;
  }
  return r.json() as Promise<WatchResponse>;
}

/** Update a watch. A POST to /modify, not a PUT — see the route. */
export async function updateWatch(
  id: number | string,
  body: UpdateWatchRequest,
  { signal, magicLinkToken }: WatchRequestOptions = {},
): Promise<WatchResponse> {
  const url = watchUrl(id, MODIFY_ACTION, magicLinkToken);
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  });
  if (!r.ok) {
    const text = await r.text().catch(() => '');
    const err = new HttpError(url, r.status);
    err.body = text;
    throw err;
  }
  return r.json() as Promise<WatchResponse>;
}

/**
 * Delete a watch. A POST to /delete, not an HTTP DELETE.
 *
 * Swallows a 404: the watch is gone either way, and a double-click on Delete
 * should not raise. Answers 204 on success, so there is nothing to return.
 */
export async function deleteWatch(
  id: number | string,
  { signal, magicLinkToken }: WatchRequestOptions = {},
): Promise<void> {
  const url = watchUrl(id, DELETE_ACTION, magicLinkToken);
  const r = await fetch(url, { method: 'POST', signal });
  if (!r.ok && r.status !== 404) {
    throw new HttpError(url, r.status);
  }
}
