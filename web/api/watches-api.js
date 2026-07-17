// web/api/watches-api.js
//
// Client for /api/watches actions. Watches are user intent for
// availability polling; the backend persists them and (in later PRs) a
// scheduler will turn them into actual polling jobs.

import { HttpError, jsonGetOk } from './http.js';

const BASE = '/api/watches';
const MODIFY_ACTION = 'modify';
const DELETE_ACTION = 'delete';

function watchUrl(id, action) {
  const base = `${BASE}/${encodeURIComponent(id)}`;
  return action ? `${base}/${action}` : base;
}

/**
 * @param {object}        [params]
 * @param {string}        [params.status]        active | paused | done
 * @param {number|string} [params.poiId]
 * @param {number|string} [params.campsiteId]
 * @param {number}        [params.limit]
 * @param {number}        [params.offset]
 * @param {AbortSignal}   [params.signal]
 */
export function listWatches({ status, poiId, campsiteId, limit, offset, signal } = {}) {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (poiId != null && poiId !== '') qs.set('poi_id', poiId);
  if (campsiteId != null && campsiteId !== '') qs.set('campsite_id', campsiteId);
  if (limit != null) qs.set('limit', limit);
  if (offset != null) qs.set('offset', offset);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`${BASE}${suffix}`, { signal });
}

export function getWatch(id, { signal } = {}) {
  return jsonGetOk(watchUrl(id), { signal });
}

export async function createWatch(body, { signal } = {}) {
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
  return r.json();
}

export async function updateWatch(id, body, { signal } = {}) {
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
  return r.json();
}

export async function deleteWatch(id, { signal } = {}) {
  const url = watchUrl(id, DELETE_ACTION);
  const r = await fetch(url, { method: 'POST', signal });
  if (!r.ok && r.status !== 404) {
    throw new HttpError(url, r.status);
  }
}
