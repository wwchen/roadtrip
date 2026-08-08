// Display formatters for the availability dashboard.
//
// Each of these was duplicated verbatim across the three legacy tab modules
// (`web/components/availability/{pollers,runs,snapshots}-tab.js`) — three copies
// of `formatTimestamp`, two of nothing else in common. Ported once here.
//
// `escapeHtml` is deliberately NOT ported: it existed because those modules built
// HTML strings, and React escapes text nodes itself. Porting it would invite
// double-escaping.
import { parseLocalYmd } from './local-date';

const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;

/**
 * Time units, exported because the dashboard's relative-time labels need the same
 * ones and two private copies would be one too many.
 */
export const SECONDS_PER_MINUTE = 60;
export const SECONDS_PER_HOUR = 3600;
export const SECONDS_PER_DAY = 86400;

/** Distinct from SECONDS_PER_MINUTE despite the shared 60: different question. */
const MINUTES_PER_HOUR = 60;

/**
 * An ISO timestamp as the dashboard shows it: `2026-07-08 14:30:00`.
 *
 * Purely textual, exactly as the legacy helper was — it drops the `T`, any
 * fractional seconds, and a trailing `Z` without parsing the string. That means
 * **no timezone conversion**: a UTC timestamp is displayed in UTC. Reading it as
 * a Date and reformatting would silently shift every column on this page to the
 * viewer's timezone, which for an operational dashboard whose backend logs in UTC
 * would make the values disagree with the logs beside them.
 */
export function formatTimestamp(iso: string): string {
  return iso.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '');
}

/**
 * A duration in seconds as `45s`, `3m 20s`, or `2h 15m`.
 *
 * Note the asymmetry, kept from the original: minutes carry their seconds
 * (`3m 20s`) but hours drop theirs (`2h 15m`).
 */
export function formatDuration(sec: number): string {
  if (sec < SECONDS_PER_MINUTE) return `${sec}s`;
  const minutes = Math.floor(sec / SECONDS_PER_MINUTE);
  const seconds = sec % SECONDS_PER_MINUTE;
  if (minutes < MINUTES_PER_HOUR) return `${minutes}m ${seconds}s`;
  const hours = Math.floor(minutes / MINUTES_PER_HOUR);
  return `${hours}h ${minutes % MINUTES_PER_HOUR}m`;
}

/**
 * Clip to `max` characters, with an ellipsis replacing the last one.
 *
 * The result is at most `max` characters INCLUDING the ellipsis — the original
 * sliced to `max - 1` and appended, and run-error cells depend on that width.
 */
export function truncate(value: string, max: number): string {
  return value.length > max ? `${value.slice(0, max - 1)}…` : value;
}

/**
 * The weekday abbreviation for a `YYYY-MM-DD` string, in LOCAL time.
 *
 * Local, not UTC, and that is the original behaviour (`new Date(d + 'T00:00:00')`
 * with no `Z`): the target date is a calendar date at the campground, so the
 * weekday a human reads off it should be the one they would say out loud. Uses
 * `parseLocalYmd` so this page and the map agree on what a bare date means.
 */
export function dayOfWeek(date: string): string {
  return WEEKDAY_LABELS[parseLocalYmd(date).getDay()] ?? '';
}
