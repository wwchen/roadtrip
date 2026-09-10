// Reading a per-day availability row.
//
// A day carries one cell per campsite, so a count is a filter over `cells` and
// nothing here decides what a status means.
import type { AvailabilityCell, AvailabilityDay } from '@/api/availability-api';

type Day = Pick<AvailabilityDay, 'cells'> | null | undefined;

/**
 * An array or a scalar in `cells` is no cells at all: `Object.entries` would read
 * an array as a map keyed by index and invent campsite ids nobody sent.
 */
function cells(day: Day): Record<string, AvailabilityCell> {
  const value = day?.cells;
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return {};
  return value;
}

/** Bookable campsite ids for a day, as strings — the order the backend sent. */
export function availableCampsiteIds(day: Day): string[] {
  return Object.entries(cells(day))
    .filter(([, cell]) => cell?.status === 'available')
    .map(([id]) => id);
}

export function availableCount(day: Day): number {
  return availableCampsiteIds(day).length;
}

export function campsiteCount(day: Day): number {
  return Object.keys(cells(day)).length;
}
