// Client for /api/watches. Typed port of web/api/watches-api.js.
//
// Watches are user intent for availability polling; the backend persists them
// and a scheduler turns them into polling jobs.
//
// DTOs are pinned against the backend (AvailabilityWatchSchema and friends).
// Note the envelopes: the list route answers
// `{ total, limit, offset, watches }` and the single-watch routes answer
// `{ watch, watch_capabilities? }`. Callers unwrap, which is what the vanilla
// callers already did (`data?.watches`, `created.watch`) — the earlier
// provisional types in this file claimed bare `Watch`/`Watch[]` and were wrong.
import type { Campsite } from './campsite-api';
import { HttpError, jsonGetOk, type RequestOptions } from './http';

const BASE = '/api/watches';
const MODIFY_ACTION = 'modify';
const DELETE_ACTION = 'delete';

/** The three values WatchStatus.parse accepts. */
export type WatchStatus = 'active' | 'paused' | 'done';

/**
 * What a watch is pointed at. A watch has one or more targets; `poi_id` watches
 * a whole POI, `campsite_id` a single site.
 */
export interface WatchTarget {
  poi_id?: number | null;
  campsite_id?: number | null;
}

/** Mirrors AvailabilityWatchSchema. */
export interface Watch {
  id: number;
  targets: WatchTarget[];
  /** Convenience mirror of the first target; null for a multi-target watch. */
  poi_id?: number | null;
  campsite_id?: number | null;
  /** Hydrated campsite row, when the watch targets a single site. */
  campsite?: Campsite | null;
  campsite_filters: Record<string, unknown>;
  start_date: string;
  end_date: string;
  /** Null when the watch carries no cadence override — the resolver falls
   *  through to the POI override, then the global default. */
  cadence_sec?: number | null;
  trigger_kinds: string[];
  trigger_config: Record<string, unknown>;
  stop_when_triggered: boolean;
  status: WatchStatus;
  created_at: string;
  updated_at: string;
  /**
   * Freshness/error of the most recent poll run across this watch's poller(s).
   * All null before the first run; `last_run_at` is the run's completed_at, so it
   * stays null while a run is in flight. Read-only — sourced from
   * availability_run and never accepted on create/update.
   */
  last_run_at?: string | null;
  last_run_status?: string | null;
  last_run_error?: string | null;
}

/** Mirrors AvailabilityWatchCapabilitiesDto. */
export interface WatchCapabilities {
  trigger_kinds: string[];
  booking_actions: string[];
}

/** Mirrors AvailabilityWatchListResponse — the GET /api/watches envelope. */
export interface WatchListResponse {
  total: number;
  limit: number;
  offset: number;
  watches: Watch[];
}

/** Mirrors AvailabilityWatchResponse — the single-watch envelope. */
export interface WatchResponse {
  watch: Watch;
  watch_capabilities?: WatchCapabilities | null;
}

/** Mirrors AvailabilityWatchCreateRequest. */
export interface CreateWatchRequest {
  targets?: WatchTarget[];
  poi_id?: number | null;
  campsite_id?: number | null;
  campsite_filters?: Record<string, unknown>;
  start_date: string;
  end_date: string;
  cadence_sec?: number | null;
  trigger_kinds: string[];
  trigger_config?: Record<string, unknown>;
  stop_when_triggered?: boolean;
}

/** Mirrors AvailabilityWatchUpdateRequest — every field is a partial update. */
export interface UpdateWatchRequest {
  targets?: WatchTarget[];
  campsite_filters?: Record<string, unknown>;
  start_date?: string;
  end_date?: string;
  cadence_sec?: number | null;
  trigger_kinds?: string[];
  trigger_config?: Record<string, unknown>;
  stop_when_triggered?: boolean;
  status?: WatchStatus;
}

export interface ListWatchesParams extends RequestOptions {
  status?: WatchStatus;
  poiId?: number | string;
  campsiteId?: number | string;
  limit?: number;
  offset?: number;
}

function watchUrl(id: number | string, action?: string): string {
  const base = `${BASE}/${encodeURIComponent(String(id))}`;
  return action ? `${base}/${action}` : base;
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
  { signal }: RequestOptions = {},
): Promise<WatchResponse> {
  return jsonGetOk<WatchResponse>(watchUrl(id), { signal });
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
  { signal }: RequestOptions = {},
): Promise<WatchResponse> {
  const url = watchUrl(id, MODIFY_ACTION);
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
  { signal }: RequestOptions = {},
): Promise<void> {
  const url = watchUrl(id, DELETE_ACTION);
  const r = await fetch(url, { method: 'POST', signal });
  if (!r.ok && r.status !== 404) {
    throw new HttpError(url, r.status);
  }
}
