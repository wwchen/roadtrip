// Reading a per-day availability row.
//
// A day carries one cell per campsite, so a count is a filter over `cells` and
// nothing here decides what a status means.
import type { AvailabilityDay } from '@/api/availability-api';

type Day = Pick<AvailabilityDay, 'cells'> | null | undefined;

function cells(day: Day): Record<string, { status: string }> {
  return day?.cells ?? {};
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
