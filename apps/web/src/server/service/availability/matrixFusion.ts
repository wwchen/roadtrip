import type { AvailabilityCacheBlock, AvailabilityDayDto, AvailabilityStatusDto, AvailabilityWindowState } from '../../contract';
import type { StoredCell } from '../../repo/availabilityReadRepo';
import { addDays } from '../booking/dateContext';

const MS_PER_SECOND = 1000;
const ROLLUP_PRECEDENCE: readonly AvailabilityStatusDto[] = ['available', 'first_come', 'unknown', 'reserved'];

export interface FusedWeek {
  state: AvailabilityWindowState;
  days: AvailabilityDayDto[];
}

export function datesOf(startDate: string, endDate: string): string[] {
  const dates: string[] = [];
  for (let date = startDate; date < endDate; date = addDays(date, 1)) dates.push(date);
  return dates;
}

export function rollupStatus(statuses: readonly AvailabilityStatusDto[]): AvailabilityStatusDto {
  if (statuses.length === 0) return 'unknown';
  const leading = ROLLUP_PRECEDENCE.find((status) => statuses.includes(status));
  if (leading !== undefined) return leading;
  return statuses.every((status) => status === 'closed') ? 'closed' : 'unknown';
}

function statusIndex(cells: readonly StoredCell[]): Map<string, AvailabilityStatusDto> {
  return new Map(cells.map((cell) => [`${cell.campsiteId}|${cell.date}`, cell.status]));
}

export function fuseWeek(
  campsiteIds: readonly bigint[],
  cells: readonly StoredCell[],
  startDate: string,
  endDate: string,
  seasonCampsiteIds: readonly bigint[] = campsiteIds,
): FusedWeek {
  const dates = datesOf(startDate, endDate);
  const byCell = statusIndex(cells);
  const statusOf = (campsiteId: bigint, date: string) => byCell.get(`${campsiteId}|${date}`) ?? 'unknown';
  const ordered = [...campsiteIds].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const closedForSeason =
    seasonCampsiteIds.length > 0 &&
    dates.length > 0 &&
    seasonCampsiteIds.every((id) => dates.every((date) => statusOf(id, date) === 'closed'));
  if (closedForSeason) return { state: 'closed_for_season', days: [] };
  return {
    state: 'success',
    days: dates.map((date) => {
      const dayCells = Object.fromEntries(ordered.map((id) => [String(id), { status: statusOf(id, date), watchable: false }]));
      return {
        date,
        status: rollupStatus(Object.values(dayCells).map((dayCell) => dayCell.status)),
        watchable: false,
        cells: dayCells,
      };
    }),
  };
}

export function weekCache(
  campsiteIds: readonly bigint[],
  cells: readonly StoredCell[],
  startDate: string,
  endDate: string,
  ttlSeconds: number,
  now: Date,
): AvailabilityCacheBlock | null {
  const observed = new Map(cells.map((cell) => [`${cell.campsiteId}|${cell.date}`, cell.lastObservedAt]));
  const needed = campsiteIds.flatMap((id) => datesOf(startDate, endDate).map((date) => observed.get(`${id}|${date}`)));
  if (needed.length === 0 || needed.some((at) => at === undefined)) return null;
  const oldest = Math.min(...needed.map((at) => at!.getTime()));
  return { hit: true, age_seconds: Math.floor((now.getTime() - oldest) / MS_PER_SECOND), ttl_seconds: ttlSeconds };
}

export function isWindowFresh(cache: AvailabilityCacheBlock | null): boolean {
  return cache !== null && cache.age_seconds <= cache.ttl_seconds;
}
