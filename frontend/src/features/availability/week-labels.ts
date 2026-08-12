// Date labels for the availability grid.
//
// Every one of these formats a `YYYY-MM-DD` string, and every one of them does it
// **in UTC**. That looks wrong and is not: an ISO date with no time is a calendar
// date, not an instant, and parsing it in the browser's zone makes `2026-08-11`
// render as "Aug 10" for every user west of Greenwich. Pinning `timeZone: 'UTC'`
// keeps the rendered label equal to the source string.
const UTC = 'UTC';

/** Column headers, indexed by `Date#getUTCDay`. */
export const DOW_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const;

const utcDate = (iso: string): Date => new Date(`${iso}T00:00:00Z`);

const format = (date: Date, options: Intl.DateTimeFormatOptions): string =>
  date.toLocaleDateString('en-US', { ...options, timeZone: UTC });

/** "Tue" for a date's column header. */
export function dowLabel(iso: string): string {
  const day = utcDate(iso).getUTCDay();
  return DOW_LABELS[day] ?? '';
}

/** The day-of-month for a column header, or the raw string if it will not parse. */
export function dayOfMonthLabel(iso: string): string {
  const day = parseInt(iso.slice(8, 10), 10);
  return Number.isFinite(day) ? String(day) : iso;
}

/** "Tue, Aug 11" — the day-detail and watch-popover heading. */
export function longDayLabel(iso: string): string {
  return format(utcDate(iso), { weekday: 'short', month: 'short', day: 'numeric' });
}

/**
 * "Aug 10 – 16, 2026", collapsing the month when the week does not cross one.
 *
 * `endIso` is the last *visible* day, not the exclusive window end: the label
 * describes what is on screen, and "Aug 10 – 17" over seven columns ending on the
 * 16th would be off by a day.
 */
export function formatWeekLabel(startIso: string, endIso?: string | null): string {
  if (!startIso) return '';
  const start = utcDate(startIso);
  const end = utcDate(endIso || startIso);
  if (Number.isNaN(start.getTime())) return startIso;

  const sameMonth =
    start.getUTCMonth() === end.getUTCMonth() && start.getUTCFullYear() === end.getUTCFullYear();
  const from = format(start, { month: 'short', day: 'numeric' });
  const to = sameMonth
    ? format(end, { day: 'numeric' })
    : format(end, { month: 'short', day: 'numeric' });
  return `${from} – ${to}, ${start.getUTCFullYear()}`;
}
