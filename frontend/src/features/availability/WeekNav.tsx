// Week navigation: back, the date-range label that opens a calendar, forward, and
// an "Earliest" jump.
//
// The Earliest button
// appears only when the user has paged away from the earliest bookable date — a
// permanent "today" button that does nothing most of the time trains people to
// ignore it, and the grid layout shifts to a three-column grid without it (see the
// `.has-today` / `.no-today` rules in availability.css).
import type { ReactNode } from 'react';
import { formatWeekLabel } from './week-labels';

export interface WeekNavProps {
  /** First visible day, `YYYY-MM-DD`. */
  startIso: string;
  /** Last visible day — inclusive, so the label matches the columns on screen. */
  endIso: string;
  /** Whether the "Earliest" jump is offered. */
  showEarliest: boolean;
  /** Back is disabled at the earliest date: there is nothing before it to show. */
  canGoBack: boolean;
  onPrev: () => void;
  onNext: () => void;
  onEarliest: () => void;
  /** Opens the month calendar. */
  onPickDate: () => void;
  /**
   * The month calendar, when it is open.
   *
   * Rendered as a child of this nav rather than beside it, and that placement is
   * load-bearing: `.cg-cal-host` positions itself with `position: absolute; top:
   * 100%`, so it needs a positioned ancestor that is *this row* — `.cg-week-nav` is
   * `position: relative` for exactly that reason. Rendered anywhere else it resolves
   * against the drawer instead and lands hundreds of pixels below the viewport,
   * which is what it did before this slot existed. The vanilla appended it to the
   * clicked button's `parentElement`, which is the same element.
   */
  calendar?: ReactNode;
}

export function WeekNav({
  startIso,
  endIso,
  showEarliest,
  canGoBack,
  onPrev,
  onNext,
  onEarliest,
  onPickDate,
  calendar,
}: WeekNavProps) {
  return (
    <div
      className={`cg-week-nav ${showEarliest ? 'has-today' : 'no-today'}`}
      role="group"
      aria-label="Week navigation"
    >
      {showEarliest ? (
        <button
          type="button"
          className="cg-week-today"
          aria-label="Jump to earliest date"
          onClick={onEarliest}
        >
          Earliest
        </button>
      ) : null}
      <button
        type="button"
        className="cg-week-prev"
        aria-label="Previous week"
        disabled={!canGoBack}
        onClick={onPrev}
      >
        ‹
      </button>
      <button
        type="button"
        className="cg-week-label"
        aria-label="Pick a date"
        onClick={onPickDate}
      >
        {formatWeekLabel(startIso, endIso)}
      </button>
      <button type="button" className="cg-week-next" aria-label="Next week" onClick={onNext}>
        ›
      </button>
      {calendar}
    </div>
  );
}
