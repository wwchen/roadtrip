// The Tesla-specific reasoning behind the supercharger drawer.
//
// Handles pricebook shapes, Tesla's SCREAMING_SNAKE amenity vocabulary, and a 7×24
// occupancy histogram in the site's own timezone.
//
// `now` is a parameter throughout rather than a `new Date()` at the point of use, so
// "today" and "the current hour" are testable without faking the clock.

/** A pricebook row as the tesla-locations capture stores it (RFC 0007). */
export interface Pricebook {
  feeType?: string;
  vehicleMakeType?: string;
  currencyCode?: string;
  rateBase?: number;
  uom?: string;
  isTou?: boolean;
  startTime?: string;
  endTime?: string;
}

export interface RateRow {
  /** 'header' carries the group label, 'tou' a time window, 'congestion' the idle fee. */
  kind: 'header' | 'tou' | 'congestion';
  label: string;
  rate: string;
  /** Shown beside the group label when the site does not price in USD. */
  currencyTag?: string;
}

/**
 * Format a rate as `$0.36/kWh`.
 *
 * `narrowSymbol` keeps USD and CAD as a bare `$` instead of `US$`/`CA$`; the
 * currency code is disclosed once per group instead (see `rateRows`), which reads
 * better in a panel this narrow.
 */
export function formatRate(book: Pricebook): string {
  const currency = book.currencyCode || 'USD';
  const amount = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    currencyDisplay: 'narrowSymbol',
  }).format(book.rateBase ?? 0);
  return `${amount}/${book.uom ?? ''}`;
}

/**
 * Pricebooks → the rows the drawer renders, or null for "no pricing on file".
 *
 * Only Tesla-vehicle charging fees and the congestion fee are surfaced; the capture
 * also carries other makes and fee types, which the vanilla drawer likewise ignored.
 * Time-of-use rows sort by start time so the day reads in order.
 */
export function rateRows(raw: unknown): RateRow[] | null {
  const books = parsePricebooks(raw);
  if (books.length === 0) return null;

  const charging = books.filter((b) => b.feeType === 'CHARGING' && b.vehicleMakeType === 'TSLA');
  const congestion = books.filter((b) => b.feeType === 'CONGESTION');
  const rows: RateRow[] = [];

  if (charging.length > 0) {
    const flat = charging.find((b) => !b.isTou);
    const tou = charging
      .filter((b) => b.isTou)
      .sort((a, b) => (a.startTime || '').localeCompare(b.startTime || ''));
    const currency = charging[0]?.currencyCode;
    rows.push({
      kind: 'header',
      label: 'Tesla',
      rate: flat ? formatRate(flat) : '',
      currencyTag: currency && currency !== 'USD' ? currency : undefined,
    });
    for (const book of tou) {
      // Tesla writes a window ending at midnight as 00:00; showing that as the END
      // of a day reads as 24:00.
      const end = book.endTime === '00:00' ? '24:00' : book.endTime;
      rows.push({ kind: 'tou', label: `${book.startTime}–${end}`, rate: formatRate(book) });
    }
  }

  const idle = congestion[0];
  if (idle) rows.push({ kind: 'congestion', label: 'Idle/congestion', rate: formatRate(idle) });

  return rows.length > 0 ? rows : null;
}

/**
 * MapLibre serialises nested feature properties to JSON strings, so pricebooks can
 * arrive as text. Parsed defensively — a malformed blob means "no pricing", not a
 * broken drawer.
 */
function parsePricebooks(raw: unknown): Pricebook[] {
  const value =
    typeof raw === 'string'
      ? (() => {
          try {
            return JSON.parse(raw);
          } catch {
            return [];
          }
        })()
      : raw;
  return Array.isArray(value) ? (value as Pricebook[]) : [];
}

/**
 * Amenity labels that do not title-case cleanly.
 *
 * `null` means "drop it": the 24-hour amenity duplicates the 24/7 capability pill.
 * A `Map` rather than an object literal because the keys come from upstream data.
 */
const AMENITY_OVERRIDES = new Map<string, string | null>([
  ['AMENITIES_WIFI', 'Wi-Fi'],
  ['AMENITIES_RESTROOMS', 'Restrooms'],
  ['AMENITIES_CAFE', 'Café'],
  ['AMENITIES_RESTAURANT', 'Restaurant'],
  ['AMENITIES_SHOPPING', 'Shopping'],
  ['AMENITIES_LODGING', 'Lodging'],
  ['AMENITIES_TWENTY_FOUR_HOUR', null],
]);

/** How many amenity pills the drawer will show before truncating. */
export const MAX_AMENITY_PILLS = 8;

/**
 * Tesla's amenity vocabulary as readable labels.
 *
 * `AMENITIES_FOO_BAR` → `Foo Bar`, with the overrides above taking precedence, and
 * legacy lowercase values (`wifi`) handled by the same fallback. Capped so a long
 * list cannot blow up the drawer's height.
 */
export function amenityLabels(amenities: unknown): string[] {
  if (!Array.isArray(amenities)) return [];
  return amenities
    .map((raw) => prettifyAmenity(raw))
    .filter((label): label is string => Boolean(label))
    .slice(0, MAX_AMENITY_PILLS);
}

export function prettifyAmenity(raw: unknown): string | null {
  const key = String(raw).toUpperCase();
  if (AMENITY_OVERRIDES.has(key)) return AMENITY_OVERRIDES.get(key) ?? null;
  return String(raw)
    .replace(/^AMENITIES_/i, '')
    .toLowerCase()
    .split('_')
    .map((word) => (word ? word[0]!.toUpperCase() + word.slice(1) : ''))
    .join(' ')
    .trim();
}

/** Busy-ness bucket, which the CSS colours rather than an inline style. */
export type BusyBucket = 'low' | 'medium' | 'high';

export interface BusyBar {
  hour: number;
  /** Fractional occupancy, 0-1. */
  value: number;
  /** Bar height in px, scaled against the day's peak. */
  height: number;
  bucket: BusyBucket;
  /** True for the site's current local hour. */
  now: boolean;
  label: string;
}

export interface BusyHours {
  bars: BusyBar[];
  peakLabel: string;
}

/** The bars are 4px at the floor so an empty hour is still visible, 28px at peak. */
const BAR_MIN_PX = 4;
const BAR_RANGE_PX = 24;
const HIGH_RATIO = 0.85;
const MEDIUM_RATIO = 0.5;
const HOURS_PER_DAY = 24;

const DAY_KEYS = [
  'sunday',
  'monday',
  'tuesday',
  'wednesday',
  'thursday',
  'friday',
  'saturday',
] as const;

/**
 * Today's occupancy profile as bars, or null when there is nothing to draw.
 *
 * Tesla nests the histogram once (`availabilityProfile.availabilityProfile.{day}`)
 * and keys it by lowercase weekday. The day and the "now" highlight are both
 * resolved in the SITE's timezone, not the reader's — a supercharger's busy hours
 * are a fact about where it is.
 *
 * Bars scale against the day's own peak rather than an absolute, so a quiet site
 * still shows a readable shape; that is why an all-zero day returns null instead of
 * a flat row of floors.
 */
export function busyHours(
  profile: unknown,
  siteTimeZone: string | undefined,
  now: Date = new Date(),
): BusyHours | null {
  if (!profile || typeof profile !== 'object') return null;
  const days = (profile as { availabilityProfile?: Record<string, unknown> }).availabilityProfile;
  if (!days || typeof days !== 'object') return null;

  const today = (days as Record<string, { congestionValue?: unknown }>)[weekdayAt(siteTimeZone, now)]
    ?.congestionValue;
  if (!Array.isArray(today) || today.length !== HOURS_PER_DAY) return null;

  const values = today.map((v) => (typeof v === 'number' && Number.isFinite(v) ? v : 0));
  const peak = Math.max(...values);
  if (peak <= 0) return null;

  const currentHour = hourAt(siteTimeZone, now);
  const bars = values.map((value, hour) => {
    const ratio = value / peak;
    return {
      hour,
      value,
      height: BAR_MIN_PX + Math.round(ratio * BAR_RANGE_PX),
      bucket: ratio >= HIGH_RATIO ? 'high' : ratio >= MEDIUM_RATIO ? 'medium' : 'low',
      now: hour === currentHour,
      label: `${formatHourLabel(hour)} · ${Math.round(value * 100)}% busy`,
    } satisfies BusyBar;
  });

  return { bars, peakLabel: formatHourLabel(values.indexOf(peak)) };
}

/** `12a`, `6a`, `12p`, `9p` — compact enough for a 24-tick axis. */
export function formatHourLabel(hour: number): string {
  if (hour === 0) return '12a';
  if (hour < 12) return `${hour}a`;
  if (hour === 12) return '12p';
  return `${hour - 12}p`;
}

/** The weekday at the site, falling back to the reader's zone on a bad tz string. */
function weekdayAt(timeZone: string | undefined, now: Date): string {
  try {
    return new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: timeZone || undefined })
      .format(now)
      .toLowerCase();
  } catch {
    return DAY_KEYS[now.getDay()] ?? 'sunday';
  }
}

function hourAt(timeZone: string | undefined, now: Date): number {
  try {
    const formatted = new Intl.DateTimeFormat('en-US', {
      hour: 'numeric',
      hour12: false,
      timeZone: timeZone || undefined,
    }).format(now);
    const parsed = Number.parseInt(formatted, 10);
    // `hour12: false` yields 24 for midnight in some ICU versions.
    return Number.isFinite(parsed) ? parsed % HOURS_PER_DAY : now.getHours();
  } catch {
    return now.getHours();
  }
}
