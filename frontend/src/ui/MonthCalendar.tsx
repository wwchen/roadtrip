import { useState } from 'react';
import { WEEKDAY_LABELS } from '@/lib/format';
import {
  addLocalDays,
  addLocalMonths,
  localYmd,
  parseLocalYmd,
  startOfLocalMonth,
} from '@/lib/local-date';
import './month-calendar.css';

/** Six weeks, so the grid never changes height as you page months. */
const GRID_CELLS = 42;

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
 * own, so the arrows page without the parent hearing about it. It draws no
 * surface of its own either — a popover or a panel puts it in a box.
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
  const title = month.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });

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
        <div className="rt-cal-title">{title}</div>
        <button
          type="button"
          className="rt-cal-next"
          aria-label="Next month"
          onClick={() => setMonth((current) => addLocalMonths(current, 1))}
        >
          ›
        </button>
      </div>
      <div className="rt-cal-grid">
        {WEEKDAY_LABELS.map((label) => (
          <div className="rt-cal-head" key={label}>
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
              disabled={(min !== null && iso < min) || (max !== null && iso > max)}
              onClick={() => onPick(iso)}
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
