// Client for /api/availability/jobs|runs|snapshots GETs.
// Read-only — no mutations from the dashboard.

import { jsonGetOk } from './http.js';

export function listJobs({ status, watchId, limit, offset, signal } = {}) {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (watchId != null && watchId !== '') qs.set('watch_id', watchId);
  if (limit != null) qs.set('limit', limit);
  if (offset != null) qs.set('offset', offset);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/availability/jobs${suffix}`, { signal });
}

export function getJobsSummary({ signal } = {}) {
  return jsonGetOk('/api/availability/jobs/summary', { signal });
}

export function listRunsForJob(jobId, { limit, signal } = {}) {
  const qs = new URLSearchParams();
  if (limit != null) qs.set('limit', limit);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/availability/jobs/${encodeURIComponent(jobId)}/runs${suffix}`, { signal });
}

export function listRuns({ status, jobId, since, limit, signal } = {}) {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (jobId != null && jobId !== '') qs.set('job_id', jobId);
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

export function getSnapshotsSummary(reservableRid, { signal } = {}) {
  const qs = new URLSearchParams({ reservable_rid: String(reservableRid) });
  return jsonGetOk(`/api/availability/snapshots/summary?${qs}`, { signal });
}
