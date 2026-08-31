// Reading a per-day availability row.
//
// A day carries `campsite_statuses` (every campsite → its status) and usually
// `available_campsite_ids` as well. The ids are authoritative when present; when
// they are absent they are derived from the statuses, which is what makes the
// count agree between a fused week response and a raw provider one.
import { normalizeAvailabilityStatus } from './availability-status';

/**
 * A per-day availability row, as `fuse.ts` produces and the API returns.
 *
 * Module-private: callers pass whatever their query gave them and read a count
 * off it, so exporting the shape would invite a second declaration of the same
 * response to drift against the API client's.
 */
interface DayRow {
  date: string;
  status?: unknown;
  available_campsite_ids?: unknown;
  campsite_statuses?: unknown;
}

/**
 * `{ campsiteId: status }` for a day, or `{}`.
 *
 * Arrays are rejected as well as non-objects: `campsite_statuses` arriving as `[]`
 * from a provider that ships empty collections as arrays would otherwise index by
 * position and produce campsite ids of `0`, `1`, `2`.
 */
function campsiteStatuses(day: DayRow | null | undefined): Record<string, unknown> {
  const statuses = day?.campsite_statuses;
  return statuses && typeof statuses === 'object' && !Array.isArray(statuses)
    ? (statuses as Record<string, unknown>)
    : {};
}

/** Bookable campsite ids for a day, as strings. */
export function availableCampsiteIds(day: DayRow | null | undefined): string[] {
  const ids = day?.available_campsite_ids;
  if (Array.isArray(ids)) return ids.map(String);
  return Object.entries(campsiteStatuses(day))
    .filter(([, status]) => normalizeAvailabilityStatus(status) === 'available')
    .map(([id]) => String(id));
}

export function availableCount(day: DayRow | null | undefined): number {
  return availableCampsiteIds(day).length;
}

export function campsiteCount(day: DayRow | null | undefined): number {
  return Object.keys(campsiteStatuses(day)).length;
}
