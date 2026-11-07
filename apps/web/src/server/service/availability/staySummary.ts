import type { AvailabilityEgressCellDto, AvailabilityStatusDto, StayAvailabilityStatus, StaySummaryDto } from '../../contract';
import type { CampsiteKey } from '../../repo/availabilityReadRepo';
import { providerRefKey } from '../../repo/availabilityReadRepo';
import { datesOf } from './matrixFusion';

export const FEW_LEFT_MAX_SITES = 2;
const ONLINE_BOOKABLE: AvailabilityStatusDto = 'available';
const UNANSWERED: AvailabilityStatusDto = 'unknown';
const CLOSED: AvailabilityStatusDto = 'closed';

export function fittingGroup(campsites: readonly CampsiteKey[], groupSize: number | null): CampsiteKey[] {
  if (groupSize === null) return [...campsites];
  return campsites.filter((campsite) => campsite.maxPeople === null || campsite.maxPeople >= groupSize);
}

function nightsOf(cells: readonly AvailabilityEgressCellDto[], dates: readonly string[]): AvailabilityStatusDto[] {
  const latest = new Map<string, AvailabilityEgressCellDto>();
  for (const cell of cells) {
    const held = latest.get(cell.date);
    if (held === undefined || Date.parse(cell.observed_at) >= Date.parse(held.observed_at)) latest.set(cell.date, cell);
  }
  return dates.map((date) => latest.get(date)?.status ?? UNANSWERED);
}

const rulesOutStay = (status: AvailabilityStatusDto) => status !== UNANSWERED && status !== ONLINE_BOOKABLE;

function statusOf(openSites: number, answered: readonly AvailabilityStatusDto[][]): StayAvailabilityStatus {
  if (openSites > FEW_LEFT_MAX_SITES) return 'open';
  if (openSites > 0) return 'few_left';
  if (answered.length === 0) return 'no_data';
  if (answered.every((nights) => nights.includes(CLOSED))) return 'closed_for_season';
  if (answered.every((nights) => nights.some(rulesOutStay))) return 'full';
  return 'no_data';
}

export function staySummary(
  campsites: readonly CampsiteKey[],
  cells: readonly AvailabilityEgressCellDto[],
  startDate: string,
  endDate: string,
  fromCache: boolean,
): StaySummaryDto {
  if (campsites.length === 0) return { status: 'no_data', open_sites: 0, checked_sites: 0, from_cache: false };
  const dates = datesOf(startDate, endDate);
  const byCampsite = new Map<string, AvailabilityEgressCellDto[]>();
  for (const cell of cells) {
    const key = providerRefKey(cell.campsite.data_provider, cell.campsite.data_provider_ref);
    const held = byCampsite.get(key);
    if (held === undefined) byCampsite.set(key, [cell]);
    else held.push(cell);
  }
  const streams = campsites.map((campsite) => nightsOf(byCampsite.get(campsite.key) ?? [], dates));
  const openSites = streams.filter((nights) => nights.every((status) => status === ONLINE_BOOKABLE)).length;
  const answered = streams.filter((nights) => nights.some((status) => status !== UNANSWERED));
  const observed = cells.reduce<string | undefined>(
    (oldest, cell) => (oldest === undefined || Date.parse(cell.observed_at) < Date.parse(oldest) ? cell.observed_at : oldest),
    undefined,
  );
  return {
    status: statusOf(openSites, answered),
    open_sites: openSites,
    checked_sites: campsites.length,
    ...(observed === undefined ? {} : { observed_at: observed }),
    from_cache: fromCache,
  };
}
