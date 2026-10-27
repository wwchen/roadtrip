import type { AvailabilityDay, StayAnswer, StaySummaryDto } from '@/api/availability-api';

const FIXTURE_POI_ID = 1;
const FIXTURE_OPEN_SITES = 3;
const FIXTURE_CHECKED_SITES = 12;

export function summary(over: Partial<StaySummaryDto> = {}): StayAnswer {
  return {
    poi_id: FIXTURE_POI_ID,
    outcome: 'answered',
    stay: {
      status: 'open',
      open_sites: FIXTURE_OPEN_SITES,
      checked_sites: FIXTURE_CHECKED_SITES,
      from_cache: false,
      ...over,
    },
  };
}

export function dayOpen(date: string, open: number): AvailabilityDay {
  const cells = Object.fromEntries(
    Array.from({ length: open }, (_, index) => [String(index + 1), { status: 'available' as const, watchable: false }]),
  );
  return {
    date,
    status: open > 0 ? 'available' : 'unknown',
    watchable: false,
    cells,
  };
}
