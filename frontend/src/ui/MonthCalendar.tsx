import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';
import { WEEKDAY_LABELS } from '@/lib/format';
import {
  addLocalDays,
  addLocalMonths,
  localYmd,
  parseLocalYmd,
  startOfLocalMonth,
} from '@/lib/local-date';
import { inBounds, inMonth, keyTarget, tabStop } from './month-calendar-nav';
import './month-calendar.css';

/** Six weeks, so the grid never changes height as you page months. */
const GRID_CELLS = 42;

/**
 * "Thursday, October 22, 2026" — each day button's accessible name. The visible
 * number alone cannot say which month a padding day belongs to, and the weekday
 * headers are hidden from assistive tech because this carries the weekday too.
 */
const dayName = new Intl.DateTimeFormat('en-US', { weekday: 'long', month: 'long', day: 'numeric', year: 'numeric' });

export interface MonthCalendarProps {
  /** Any `YYYY-MM-DD` in the month to open on. Paging after that is the calendar's own. */
  initialMonth: string;
  /** The day ringed as today. */
  today: string;
  /** Earliest pickable day, inclusive; earlier days are disabled. */
  min?: string | null;
  /** Latest pickable day, inclusive; later days are disabled. */
  max?: string | null;
  /** The picked day, or the first day of a picked range. */
  selectedStart?: string | null;
  /** The last day of a picked range. The days between the two ends are drawn as in range. */
  selectedEnd?: string | null;
  onPick: (iso: string) => void;
}

/**
 * A month of days to pick from, with month paging.
 *
 * Every date in and out is a `YYYY-MM-DD` string, so callers compare them as
 * strings and never see a `Date`. The grid is laid out in LOCAL time (see
 * `@/lib/local-date`), which is what makes the 11th sit under the weekday a
 * person would say out loud.
 *
 * It only reports a pick: the selection belongs to the parent, so one primitive
 * serves both a single-day pick and a range. The view month is the calendar's
 * own, so the arrows page without the parent hearing about it. It sizes itself
 * but draws no surface — a popover or a panel puts it in a box.
 *
 * The grid is one Tab stop. Arrow keys, Home/End and PageUp/PageDown (Shift for a
 * year) move the focus between days (`month-calendar-nav.ts`), paging the month
 * when the focus leaves it, and Enter or Space picks the focused day.
 */
export function MonthCalendar({
  initialMonth,
  today,
  min = null,
  max = null,
  selectedStart = null,
  selectedEnd = null,
  onPick,
}: MonthCalendarProps) {
  const [month, setMonth] = useState(() => startOfLocalMonth(parseLocalYmd(initialMonth)));
  const [focused, setFocused] = useState<string | null>(null);
  // Set by a key move and spent by the effect below, so a click or a parent
  // re-render never pulls focus into the grid.
  const focusPending = useRef(false);
  const gridRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const title = month.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
  const stop = tabStop(month, [focused, selectedStart, today], min, max);

  useEffect(() => {
    if (!focusPending.current || focused === null) return;
    focusPending.current = false;
    gridRef.current?.querySelector<HTMLButtonElement>(`[data-date="${focused}"]`)?.focus();
  }, [focused, month]);

  const onGridKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const from = (event.target as HTMLElement).dataset.date;
    const to = from ? keyTarget(from, event.key, event.shiftKey) : null;
    if (to === null) return;
    // Arrows and paging keys would otherwise scroll whatever holds the calendar.
    event.preventDefault();
    if (!inBounds(to, min, max)) return;
    focusPending.current = true;
    setFocused(to);
    if (!inMonth(to, month)) setMonth(startOfLocalMonth(parseLocalYmd(to)));
  };

  return (
    <div className="rt-cal">
      <div className="rt-cal-head-row">
        <button
          type="button"
          className="rt-cal-prev"
          aria-label="Previous month"
          onClick={() => setMonth((current) => addLocalMonths(current, -1))}
        >
          ‹
        </button>
        <div className="rt-cal-title" id={titleId} aria-live="polite">
          {title}
        </div>
        <button
          type="button"
          className="rt-cal-next"
          aria-label="Next month"
          onClick={() => setMonth((current) => addLocalMonths(current, 1))}
        >
          ›
        </button>
      </div>
      <div className="rt-cal-grid" role="group" aria-labelledby={titleId} ref={gridRef} onKeyDown={onGridKeyDown}>
        {WEEKDAY_LABELS.map((label) => (
          <div className="rt-cal-head" key={label} aria-hidden="true">
            {label}
          </div>
        ))}
        {monthCells(month).map((date) => {
          const iso = localYmd(date);
          const isEnd = iso === selectedStart || iso === selectedEnd;
          const inRange =
            selectedStart !== null && selectedEnd !== null && iso > selectedStart && iso < selectedEnd;
          const classes = [
            'rt-cal-day',
            date.getMonth() === month.getMonth() ? '' : 'rt-cal-faint',
            iso === today ? 'rt-cal-today' : '',
            isEnd ? 'rt-cal-selected' : '',
            inRange ? 'rt-cal-in-range' : '',
          ]
            .filter(Boolean)
            .join(' ');
          return (
            <button
              type="button"
              className={classes}
              key={iso}
              data-date={iso}
              aria-label={dayName.format(date)}
              aria-pressed={isEnd || inRange}
              aria-current={iso === today ? 'date' : undefined}
              tabIndex={iso === stop ? 0 : -1}
              disabled={!inBounds(iso, min, max)}
              onClick={() => {
                setFocused(iso);
                onPick(iso);
              }}
            >
              {date.getDate()}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * Six rows of seven local dates covering the month, padded with the surrounding
 * days so the calendar reads like a wall calendar.
 */
function monthCells(month: Date): Date[] {
  const first = startOfLocalMonth(month);
  const gridStart = addLocalDays(first, -first.getDay());
  return Array.from({ length: GRID_CELLS }, (_, index) => addLocalDays(gridStart, index));
}
