// Client for /api/watches. Typed port of web/api/watches-api.js.
//
// Watches are user intent for availability polling; the backend persists them
// and a scheduler turns them into polling jobs.
import { HttpError, jsonGetOk, type RequestOptions } from './http';

const BASE = '/api/watches';
const MODIFY_ACTION = 'modify';
const DELETE_ACTION = 'delete';

export type WatchStatus = 'active' | 'paused' | 'done';

export interface ListWatchesParams extends RequestOptions {
  status?: WatchStatus;
  poiId?: number | string;
  campsiteId?: number | string;
  limit?: number;
  offset?: number;
}

// Provisional shape — refined in Phase 1 when the watches feature is ported and
// the real DTO is pinned against the backend response.
export interface Watch {
  id: number | string;
  status?: WatchStatus;
  poi_id?: number | string;
  campsite_id?: number | string;
  [key: string]: unknown;
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
}: ListWatchesParams = {}): Promise<Watch[]> {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (poiId != null && poiId !== '') qs.set('poi_id', String(poiId));
  if (campsiteId != null && campsiteId !== '') qs.set('campsite_id', String(campsiteId));
  if (limit != null) qs.set('limit', String(limit));
  if (offset != null) qs.set('offset', String(offset));
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk<Watch[]>(`${BASE}${suffix}`, { signal });
}

export function getWatch(id: number | string, { signal }: RequestOptions = {}): Promise<Watch> {
  return jsonGetOk<Watch>(watchUrl(id), { signal });
}

export async function createWatch(body: unknown, { signal }: RequestOptions = {}): Promise<Watch> {
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
  return r.json() as Promise<Watch>;
}

export async function updateWatch(
  id: number | string,
  body: unknown,
  { signal }: RequestOptions = {},
): Promise<Watch> {
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
  return r.json() as Promise<Watch>;
}

export async function deleteWatch(id: number | string, { signal }: RequestOptions = {}): Promise<void> {
  const url = watchUrl(id, DELETE_ACTION);
  const r = await fetch(url, { method: 'POST', signal });
  if (!r.ok && r.status !== 404) {
    throw new HttpError(url, r.status);
  }
}
