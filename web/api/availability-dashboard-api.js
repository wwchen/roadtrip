// Client for /api/availability/pollers|runs|snapshots GETs.
// Read-only — no mutations from the dashboard.

import { jsonGetOk } from './http.js';

export function listPollers({ active, limit, offset, signal } = {}) {
  const qs = new URLSearchParams();
  if (active != null && active !== '') qs.set('active', active);
  if (limit != null) qs.set('limit', limit);
  if (offset != null) qs.set('offset', offset);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/availability/pollers${suffix}`, { signal });
}

export function getPollersSummary({ signal } = {}) {
  return jsonGetOk('/api/availability/pollers/summary', { signal });
}

export function listRunsForPoller(pollerId, { limit, signal } = {}) {
  const qs = new URLSearchParams();
  if (limit != null) qs.set('limit', limit);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/availability/pollers/${encodeURIComponent(pollerId)}/runs${suffix}`, { signal });
}

export function listRuns({ status, pollerId, since, limit, signal } = {}) {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (pollerId != null && pollerId !== '') qs.set('poller_id', pollerId);
  if (since) qs.set('since', since);
  if (limit != null) qs.set('limit', limit);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/availability/runs${suffix}`, { signal });
}

export function listSnapshotsForReservable(reservableRid, { limit, signal } = {}) {
  const qs = new URLSearchParams({ reservable_rid: String(reservableRid) });
  if (limit != null) qs.set('limit', limit);
  return jsonGetOk(`/api/availability/snapshots?${qs}`, { signal });
}

export function listSnapshotsForRun(runId, { limit, signal } = {}) {
  const qs = new URLSearchParams({ run_id: String(runId) });
  if (limit != null) qs.set('limit', limit);
  return jsonGetOk(`/api/availability/snapshots?${qs}`, { signal });
}

export function getSnapshotsSummary(reservableRid, { dates, signal } = {}) {
  const qs = new URLSearchParams({ reservable_rid: String(reservableRid) });
  if (Array.isArray(dates) && dates.length > 0) qs.set('dates', dates.join(','));
  return jsonGetOk(`/api/availability/snapshots/summary?${qs}`, { signal });
}
