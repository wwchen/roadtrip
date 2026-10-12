// Keyboard movement for `MonthCalendar`, kept apart from the component so the date
// arithmetic is tested directly. The keys are the WAI-ARIA date-grid set: arrows
// by day and week, Home/End to the week's ends, PageUp/PageDown by month, and
// with Shift by year. Every date is `YYYY-MM-DD` in local time, like the grid.
import { addLocalDays, localYmd, parseLocalYmd } from '@/lib/local-date';

const DAYS_PER_WEEK = 7;
const MONTHS_PER_YEAR = 12;

/** The day a key moves the focus to from `iso`, or null when the key is not a calendar move. */
export function keyTarget(iso: string, key: string, shiftKey: boolean): string | null {
  const date = parseLocalYmd(iso);
  switch (key) {
    case 'ArrowLeft':
      return localYmd(addLocalDays(date, -1));
    case 'ArrowRight':
      return localYmd(addLocalDays(date, 1));
    case 'ArrowUp':
      return localYmd(addLocalDays(date, -DAYS_PER_WEEK));
    case 'ArrowDown':
      return localYmd(addLocalDays(date, DAYS_PER_WEEK));
    case 'Home':
      return localYmd(addLocalDays(date, -date.getDay()));
    case 'End':
      return localYmd(addLocalDays(date, DAYS_PER_WEEK - 1 - date.getDay()));
    case 'PageUp':
      return shiftMonths(date, shiftKey ? -MONTHS_PER_YEAR : -1);
    case 'PageDown':
      return shiftMonths(date, shiftKey ? MONTHS_PER_YEAR : 1);
    default:
      return null;
  }
}

/** Whether `iso` falls inside the inclusive bounds; a null bound is open. */
export function inBounds(iso: string, min: string | null, max: string | null): boolean {
  return (min === null || iso >= min) && (max === null || iso <= max);
}

/** Whether `iso` is a day of the month `month` (any date in it) belongs to. */
export function inMonth(iso: string, month: Date): boolean {
  return iso.slice(0, 7) === localYmd(month).slice(0, 7);
}

/**
 * The one day in `month` that takes the Tab stop: the first of `preferred` that
 * is in the month and pickable, else the month's first pickable day. Null when
 * the whole month is out of bounds, since a disabled button cannot take focus.
 */
export function tabStop(
  month: Date,
  preferred: (string | null)[],
  min: string | null,
  max: string | null,
): string | null {
  const choice = preferred.find((iso): iso is string => iso !== null && inMonth(iso, month) && inBounds(iso, min, max));
  if (choice) return choice;
  for (let day = new Date(month.getFullYear(), month.getMonth(), 1); inMonth(localYmd(day), month); day = addLocalDays(day, 1)) {
    if (inBounds(localYmd(day), min, max)) return localYmd(day);
  }
  return null;
}

/** The same day of the month `months` away, clamped to that month's length (Jan 31 → Feb 28). */
function shiftMonths(date: Date, months: number): string {
  const lastDay = new Date(date.getFullYear(), date.getMonth() + months + 1, 0).getDate();
  return localYmd(new Date(date.getFullYear(), date.getMonth() + months, Math.min(date.getDate(), lastDay)));
}
