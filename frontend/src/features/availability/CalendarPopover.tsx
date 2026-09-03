// Month calendar for jumping the visible week.
//
// It keeps its own view month, so the
// arrows page months without closing, while the parent owns the selection — the
// popover only reports a pick.
//
// Dismissal is `@/lib/use-dismiss`. Paging can detach the clicked button, so the
// inner stopPropagation guard stays on top of the hook's containment test.
import { useRef, useState } from 'react';
import {
  addLocalDays,
  addLocalMonths,
  localYmd,
  parseLocalYmd,
  startOfLocalMonth,
} from '@/lib/local-date';
import { useDismiss } from '@/lib/use-dismiss';
import { DOW_LABELS } from './week-labels';

/** Six weeks, so the grid never changes height as you page months. */
const GRID_CELLS = 42;

export interface CalendarPopoverProps {
  /** The month to open on — the visible week's start. */
  viewMonth: Date;
  /** The earliest selectable day, and the one marked "today". */
  today: Date;
  /** Highlighted day: the visible week's start. */
  selectedDate: Date | null;
  /** The provider's booking horizon. */
  maxDate: Date;
  onPick: (date: Date) => void;
  onClose: () => void;
}

export function CalendarPopover({
  viewMonth,
  today,
  selectedDate,
  maxDate,
  onPick,
  onClose,
}: CalendarPopoverProps) {
  const [month, setMonth] = useState(() => startOfLocalMonth(viewMonth));
  const hostRef = useRef<HTMLDivElement>(null);

  useDismiss(hostRef, onClose);

  const todayIso = localYmd(today);
  const selectedIso = selectedDate ? localYmd(selectedDate) : null;
  const maxIso = localYmd(maxDate);
  const title = month.toLocaleDateString('en-US', { month: 'long', year: 'numeric' });

  return (
    <div
      className="cg-cal-host"
      ref={hostRef}
      // Every click in here came from inside the popover, so it is never an
      // outside click — stop it before the document listener sees it. Paging a
      // month unmounts the clicked button, which would defeat a contains() check.
      onClick={(event) => event.stopPropagation()}
    >
      <div className="cg-cal-popover" role="dialog" aria-label="Pick a week">
        <div className="cg-cal-head-row">
          <button
            type="button"
            className="cg-cal-prev"
            aria-label="Previous month"
            onClick={() => setMonth((current) => addLocalMonths(current, -1))}
          >
            ‹
          </button>
          <div className="cg-cal-title">{title}</div>
          <button
            type="button"
            className="cg-cal-next"
            aria-label="Next month"
            onClick={() => setMonth((current) => addLocalMonths(current, 1))}
          >
            ›
          </button>
        </div>
        <div className="cg-cal-grid">
          {DOW_LABELS.map((label) => (
            <div className="cg-cal-head" key={label}>
              {label}
            </div>
          ))}
          {monthCells(month).map((date) => {
            const iso = localYmd(date);
            const classes = [
              'cg-cal-cell',
              'cg-cal-day',
              date.getMonth() === month.getMonth() ? '' : 'cg-cal-faint',
              iso === todayIso ? 'cg-cal-today' : '',
              iso === selectedIso ? 'cg-cal-selected' : '',
            ]
              .filter(Boolean)
              .join(' ');
            return (
              <button
                type="button"
                className={classes}
                key={iso}
                data-date={iso}
                disabled={iso < todayIso || iso > maxIso}
                onClick={() => onPick(parseLocalYmd(iso))}
              >
                {date.getDate()}
              </button>
            );
          })}
        </div>
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
