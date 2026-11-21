import type { AvailabilityDay } from '@/api/availability-api';
import type { Campsite, PoiCampsitesResponse } from '@/api/campsite-api';
import type { Watch } from '@/api/watches-api';
import { addLocalDays, localYmd, parseLocalYmd } from '@/lib/local-date';
import { stayEndDate } from '@/lib/watch-windows';
import { WEEK_DAYS } from './useWeekAvailability';

export const STORY_POI_ID = 4242;
export const STORY_POI_NAME = 'Fallen Leaf';
export const STORY_WEEK_START = '2026-09-08';

export const STORY_WEEK_DATES: readonly string[] = Array.from({ length: WEEK_DAYS }, (_, index) =>
  localYmd(addLocalDays(parseLocalYmd(STORY_WEEK_START), index)),
);

export const STORY_WATCHED_DATE = STORY_WEEK_DATES[4];

export function storyCampsite(
  over: Partial<Campsite> & Pick<Campsite, 'id' | 'name' | 'kind' | 'kind_label'>,
): Campsite {
  return {
    campground_id: 1,
    equipment: [],
    attributes: [],
    data_provider: 'recgov',
    data_provider_ref: String(over.id),
    booking_system: 'Recreation.gov',
    ...over,
  };
}

export const STORY_CAMPSITES: Campsite[] = [
  storyCampsite({ id: 101, name: 'A12', kind: 'standard', kind_label: 'Standard campsite', loop_name: 'Loop A' }),
  storyCampsite({ id: 102, name: 'A14', kind: 'standard', kind_label: 'Standard campsite', loop_name: 'Loop A' }),
  storyCampsite({
    id: 103,
    name: 'B02',
    kind: 'rv',
    kind_label: 'RV site with hookups',
    loop_name: 'Loop B',
    electric_hookups: true,
    water_hookups: true,
  }),
  storyCampsite({ id: 104, name: 'B05', kind: 'rv', kind_label: 'RV site with hookups', loop_name: 'Loop B' }),
  storyCampsite({ id: 105, name: 'C01', kind: 'standard', kind_label: 'Standard campsite', loop_name: 'Loop C' }),
];

const STATUS_ROWS: Record<number, AvailabilityDay['status'][]> = {
  101: ['available', 'reserved', 'available', 'available', 'unknown', 'closed', 'available'],
  102: ['reserved', 'reserved', 'available', 'reserved', 'reserved', 'reserved', 'available'],
  103: ['available', 'available', 'available', 'available', 'reserved', 'available', 'available'],
  104: ['first_come', 'first_come', 'unknown', 'first_come', 'reserved', 'first_come', 'first_come'],
  105: ['unknown', 'unknown', 'unknown', 'unknown', 'reserved', 'unknown', 'unknown'],
};

const WATCHABLE_STATUSES: ReadonlySet<AvailabilityDay['status']> = new Set(['reserved', 'first_come']);

export const STORY_RESERVATION_URL_TEMPLATES: Record<string, string> = Object.fromEntries(
  STORY_CAMPSITES.map((site) => [String(site.id), 'https://booking.example.com/sites/{start_date}/{end_date}']),
);

function dayStatus(cells: AvailabilityDay['cells']): AvailabilityDay['status'] {
  const statuses = Object.values(cells).map((cell) => cell.status);
  if (statuses.includes('available')) return 'available';
  if (statuses.includes('reserved')) return 'reserved';
  return statuses[0] ?? 'unknown';
}

export function storyDay(date: string, dayIndex: number): AvailabilityDay {
  const cells: AvailabilityDay['cells'] = Object.fromEntries(
    STORY_CAMPSITES.map((site) => {
      const row = STATUS_ROWS[site.id];
      const status = row[dayIndex % row.length];
      return [String(site.id), { status, watchable: WATCHABLE_STATUSES.has(status) }];
    }),
  );
  const status = dayStatus(cells);
  return { date, status, watchable: Object.values(cells).some((cell) => cell.watchable), cells };
}

export function storyWeekDays(startIso: string): AvailabilityDay[] {
  const start = parseLocalYmd(startIso);
  return Array.from({ length: WEEK_DAYS }, (_, index) => storyDay(localYmd(addLocalDays(start, index)), index));
}

export const STORY_WEEK_DAYS: readonly AvailabilityDay[] = storyWeekDays(STORY_WEEK_START);

export const STORY_FULL_DAY: AvailabilityDay = STORY_WEEK_DAYS[4];

export const STORY_OPEN_DAY: AvailabilityDay = STORY_WEEK_DAYS[0];

export const STORY_CAMPSITES_RESPONSE: PoiCampsitesResponse = {
  poi_id: STORY_POI_ID,
  type: 'campground',
  campsites: STORY_CAMPSITES,
  reservation_url_templates: STORY_RESERVATION_URL_TEMPLATES,
};

export function storyWatch(over: Partial<Watch> & Pick<Watch, 'id' | 'start_date'>): Watch {
  return {
    poi_id: STORY_POI_ID,
    status: 'active',
    targets: [],
    campsite_filters: {},
    end_date: stayEndDate(over.start_date),
    trigger_kinds: ['slack_notify'],
    trigger_config: {},
    stop_when_triggered: true,
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    ...over,
  };
}

export const STORY_WATCH: Watch = storyWatch({ id: 501, start_date: STORY_WATCHED_DATE });
