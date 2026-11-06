import tzLookup from '@photostructure/tz-lookup';

export const DEFAULT_TIME_ZONE = 'America/Vancouver';
const EARLIEST_DATE_CUTOFF_HOUR = 18;
const MAX_LATITUDE = 90;
const MAX_LONGITUDE = 180;
const ISO_DATE_LOCALE = 'en-CA';
const MS_PER_DAY = 86_400_000;

export interface PoiDateContext {
  timeZone: string;
  earliestDate: string;
}

function inRange(value: number | null | undefined, limit: number): value is number {
  return typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= limit;
}

export function timeZoneAt(lat: number | null | undefined, lng: number | null | undefined): string {
  if (!inRange(lat, MAX_LATITUDE) || !inRange(lng, MAX_LONGITUDE)) return DEFAULT_TIME_ZONE;
  try {
    return tzLookup(lat, lng);
  } catch {
    return DEFAULT_TIME_ZONE;
  }
}

function localDate(instant: Date, timeZone: string): string {
  return new Intl.DateTimeFormat(ISO_DATE_LOCALE, { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(instant);
}

function localHour(instant: Date, timeZone: string): number {
  return Number(new Intl.DateTimeFormat(ISO_DATE_LOCALE, { timeZone, hour: '2-digit', hourCycle: 'h23' }).format(instant));
}

export function addDays(isoDate: string, days: number): string {
  return new Date(Date.parse(`${isoDate}T00:00:00Z`) + days * MS_PER_DAY).toISOString().slice(0, 10);
}

export function poiDateContext(lat: number | null | undefined, lng: number | null | undefined, now: Date): PoiDateContext {
  const timeZone = timeZoneAt(lat, lng);
  const today = localDate(now, timeZone);
  const earliestDate = localHour(now, timeZone) < EARLIEST_DATE_CUTOFF_HOUR ? today : addDays(today, 1);
  return { timeZone, earliestDate };
}
