// Every function here works in the browser's LOCAL time zone on purpose.
// Campsite availability is a calendar fact ("the night of the 8th"), not an
// instant, so a UTC round-trip would shift the user's date by a day either side
// of midnight. `Date` is used as a local-calendar carrier; only `localYmd`
// produces the wire format.

/** Local midnight today. */
export function localToday(): Date {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate());
}

/** Local calendar date as `YYYY-MM-DD` (the API's date format). */
export function localYmd(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * Parse `YYYY-MM-DD` as local midnight.
 *
 * Returns an Invalid Date (not a throw, and not a fallback to today) for
 * anything else, so callers can reject bad deep-link params with `isNaN`.
 * Deliberately not `new Date(value)`, which parses a bare date string as UTC.
 */
export function parseLocalYmd(value: unknown): Date {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value || ''));
  if (!match) return new Date(Number.NaN);
  const [, y, m, d] = match;
  return new Date(Number(y), Number(m) - 1, Number(d));
}

export function addLocalDays(date: Date, days: number): Date {
  const next = new Date(date);
  next.setDate(date.getDate() + days);
  return next;
}

export function startOfLocalMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1);
}

export function addLocalMonths(date: Date, months: number): Date {
  return new Date(date.getFullYear(), date.getMonth() + months, 1);
}

export function sameLocalDay(a: Date, b: Date): boolean {
  return localYmd(a) === localYmd(b);
}
