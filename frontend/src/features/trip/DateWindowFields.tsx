// Start and end date entry for the Check availability step. M2 stores the window
// only; M3 polls with it.
//
// One calendar picks both ends, and a Start/End control above it says which end
// the next pick sets (`nextDraft`). Picking walks start → end → start, so two
// clicks make a fresh window; choosing End first changes only the end, which is
// how a saved stay gains or loses a night. A pick that leaves the end on or
// before the start drops the end, and an end pick on or before the start becomes
// the new start, so every window Save can see is two dates in order.
//
// A calendar rather than two `type="date"` fields because a stay is a range: one
// grid shows both ends and the nights between them, and keeps the start from
// being placed after the end.
import { useEffect, useRef, useState } from 'react';
import { Button, MonthCalendar, SegmentedControl } from '@ui';
import { localToday, localYmd } from '@/lib/local-date';
import { filterCopy } from '@/lib/strings';
import type { DateWindow } from '@/stores/campgroundFilterStore';

const MS_PER_DAY = 86_400_000;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

const shortDate = new Intl.DateTimeFormat('en-US', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });

/** Whole nights between two ISO dates; 0 or less means the window is not valid. */
export function nightsBetween(start: string, end: string): number {
  return Math.round((Date.parse(end) - Date.parse(start)) / MS_PER_DAY);
}

/**
 * "Fri Sep 11" from "2026-09-11"; UTC so the calendar date never shifts.
 * Returns input unchanged if not a valid ISO date, since `Intl` throws on an
 * invalid `Date`. `en-US`'s weekday+month+day format inserts a comma after the
 * weekday ("Fri, Sep 11") — stripped here since the wanted form has none.
 */
export function formatDateShort(iso: string): string {
  const date = new Date(`${iso}T00:00:00Z`);
  if (!ISO_DATE.test(iso) || Number.isNaN(date.getTime())) return iso;
  return shortDate.format(date).replace(',', '');
}

/** Which end of the window the next pick sets. */
export type DateEnd = 'start' | 'end';

/** A window part-way through picking: either end may still be missing. */
export interface DateDraft {
  start: string | null;
  end: string | null;
  picking: DateEnd;
}

/**
 * The draft an editor opens on. A saved end that is not after today has passed,
 * and a saved start before today has too; neither is offered back, because the
 * calendar would draw it on a day it will not let anyone pick, and Save would
 * write a stay that has already begun.
 */
export function seedDraft(initial: DateWindow | null, today: string): DateDraft {
  return {
    start: initial !== null && initial.start >= today ? initial.start : null,
    end: initial !== null && initial.end > today ? initial.end : null,
    picking: 'start',
  };
}

/** The draft after a click on `day`; the file comment has the rule. */
export function nextDraft(draft: DateDraft, day: string): DateDraft {
  if (draft.picking === 'end' && draft.start !== null && day > draft.start) {
    return { start: draft.start, end: day, picking: 'start' };
  }
  return { start: day, end: draft.end !== null && draft.end > day ? draft.end : null, picking: 'end' };
}

/** What the next click does, or how long the picked window is. */
function draftHint({ start, end }: DateDraft): string {
  if (start === null) return filterCopy.pickStart;
  if (end === null) return filterCopy.pickEnd;
  return filterCopy.nights(nightsBetween(start, end));
}

export interface DateWindowFieldsProps {
  initial: DateWindow | null;
  onSave: (window: DateWindow) => void;
  onCancel: () => void;
}

export function DateWindowFields({ initial, onSave, onCancel }: DateWindowFieldsProps) {
  const [today] = useState(() => localYmd(localToday()));
  const [draft, setDraft] = useState(() => seedDraft(initial, today));
  const rootRef = useRef<HTMLDivElement>(null);
  const { start, end, picking } = draft;

  // The panel above the map is height-capped and scrolls, and the filters sit
  // above this step, so on a short viewport the calendar opens partly below the
  // fold. Bring the whole editor, Save included, into view once when it opens.
  // Optional call: jsdom has no `scrollIntoView`.
  useEffect(() => {
    rootRef.current?.scrollIntoView?.({ block: 'nearest' });
  }, []);

  return (
    <div className="tb-dates" ref={rootRef}>
      <div className="tb-dates-picker">
        <SegmentedControl
          size="sm"
          full
          name="date_window_end"
          label={filterCopy.datePicking}
          options={[
            { value: 'start', label: filterCopy.startSlot(start === null ? null : formatDateShort(start)) },
            { value: 'end', label: filterCopy.endSlot(end === null ? null : formatDateShort(end)) },
          ]}
          value={picking}
          onChange={(next) => setDraft((current) => ({ ...current, picking: next as DateEnd }))}
        />
        <MonthCalendar
          initialMonth={start ?? today}
          today={today}
          min={today}
          selectedStart={start}
          selectedEnd={end}
          onPick={(day) => setDraft((current) => nextDraft(current, day))}
        />
      </div>
      <div className="tb-dates-actions">
        <span className="tb-dates-hint" aria-live="polite">
          {draftHint(draft)}
        </span>
        <Button variant="tertiary" size="sm" onClick={onCancel}>
          {filterCopy.cancelDates}
        </Button>
        <Button
          variant="primary"
          size="sm"
          disabled={start === null || end === null}
          onClick={() => {
            if (start !== null && end !== null) onSave({ start, end });
          }}
        >
          {filterCopy.saveDates}
        </Button>
      </div>
    </div>
  );
}
