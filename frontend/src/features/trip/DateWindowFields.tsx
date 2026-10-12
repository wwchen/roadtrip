// The date window's two fields, in the filter block with the other filters. M2
// stores the window only; M3 polls with it.
//
// Like the filters beside it, it applies as it changes: the window is reported
// the moment both ends are set, and reported as gone as soon as either is
// missing, so the store never holds a window the fields do not show. Clear
// empties both.
//
// The calendar is shown only while one of the fields is selected. Selecting a field opens the calendar to set that end; each pick
// fills it and moves on to whichever end is still missing, and the calendar
// closes once both are set (`nextDraft`). Selecting the open field again, a click
// outside, or Escape also closes it. So a fresh window is Start and two days; a
// saved stay gains a night with End and one day.
//
// A pick that leaves the end on or before the start drops the end, and an end
// pick on or before the start becomes the new start, so every window reported is
// two dates in order.
//
// The fields are disclosure buttons drawn as fields rather than LDS `TextField`s:
// LDS drops `onClick`/`onFocus`/`onKeyDown` on its template components and
// rebuilds their DOM on every prop change, so a field that toggles
// `aria-expanded` would lose focus the moment it opened.
import { useCallback, useEffect, useId, useRef, useState, type KeyboardEvent, type RefObject } from 'react';
import { Button, Icon, MonthCalendar } from '@ui';
import { localToday, localYmd } from '@/lib/local-date';
import { filterCopy } from '@/lib/strings';
import { useDismiss } from '@/lib/use-dismiss';
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

/** One end of the window. */
export type DateEnd = 'start' | 'end';

/** A window part-way through picking: either end may still be missing. */
export interface DateDraft {
  start: string | null;
  end: string | null;
  /** The end the calendar is open to set, or null when it is closed. */
  picking: DateEnd | null;
}

/**
 * The draft an editor opens on, with the calendar closed. A saved end that is
 * not after today has passed, and a saved start before today has too; neither is
 * offered back, because the calendar would draw it on a day it will not let
 * anyone pick, and Save would write a stay that has already begun.
 */
export function seedDraft(initial: DateWindow | null, today: string): DateDraft {
  return {
    start: initial !== null && initial.start >= today ? initial.start : null,
    end: initial !== null && initial.end > today ? initial.end : null,
    picking: null,
  };
}

/** The end still missing from a window, the start first; null when both are set. */
function missingEnd(start: string | null, end: string | null): DateEnd | null {
  if (start === null) return 'start';
  return end === null ? 'end' : null;
}

/** The draft after a click on `day`; the file comment has the rule. */
export function nextDraft(draft: DateDraft, day: string): DateDraft {
  const setsEnd = draft.picking === 'end' && (draft.start === null || day > draft.start);
  const start = setsEnd ? draft.start : day;
  const end = setsEnd ? day : draft.end !== null && draft.end > day ? draft.end : null;
  return { start, end, picking: missingEnd(start, end) };
}

/** The window a draft stands for: both ends, or nothing. */
function windowOf({ start, end }: DateDraft): DateWindow | null {
  return start !== null && end !== null ? { start, end } : null;
}

/**
 * How long the picked window is; otherwise what the next pick sets, or — with
 * nothing picked and the calendar closed — what the dates are for.
 */
function draftHint(draft: DateDraft): string {
  const { start, end, picking } = draft;
  if (start !== null && end !== null) return filterCopy.nights(nightsBetween(start, end));
  if (picking === null && start === null && end === null) return filterCopy.datesPurpose;
  const next = picking ?? missingEnd(start, end);
  return next === 'start' ? filterCopy.pickStart : filterCopy.pickEnd;
}

export interface DateWindowFieldsProps {
  /** The stored window, read once when the fields mount. */
  initial: DateWindow | null;
  /** Called with the window whenever it changes, and with null when it is incomplete or cleared. */
  onChange: (window: DateWindow | null) => void;
}

export function DateWindowFields({ initial, onChange }: DateWindowFieldsProps) {
  const [today] = useState(() => localYmd(localToday()));
  const [draft, setDraft] = useState(() => seedDraft(initial, today));
  const rootRef = useRef<HTMLDivElement>(null);
  const pickerRef = useRef<HTMLDivElement>(null);
  const fieldRefs = { start: useRef<HTMLButtonElement>(null), end: useRef<HTMLButtonElement>(null) };
  // The field to hand focus back to once the calendar closes under it — set by a
  // pick that completes the window and by Escape, never by a click outside,
  // which has already put the focus where the person wanted it.
  const refocus = useRef<DateEnd | null>(null);
  const calendarId = useId();
  const { start, end, picking } = draft;

  useEffect(() => {
    if (picking !== null || refocus.current === null) return;
    fieldRefs[refocus.current].current?.focus();
    refocus.current = null;
  }, [picking]);

  // Once, at mount: a stored window with a passed end is not offered back
  // (`seedDraft`), so the store must not keep holding it either.
  useEffect(() => {
    const seeded = windowOf(draft);
    if (seeded?.start !== initial?.start || seeded?.end !== initial?.end) onChange(seeded);
    // Mount only: every later change is reported by `pick` and `clear`.
  }, []);

  // Stable, because `useDismiss` re-attaches its listeners whenever this changes.
  const close = useCallback(
    () => setDraft((current) => (current.picking === null ? current : { ...current, picking: null })),
    [],
  );
  const toggle = (which: DateEnd): void =>
    setDraft((current) => ({ ...current, picking: current.picking === which ? null : which }));
  const pick = (day: string): void => {
    const next = nextDraft(draft, day);
    if (next.picking === null) refocus.current = draft.picking;
    setDraft(next);
    onChange(windowOf(next));
  };
  const clear = (): void => {
    setDraft({ start: null, end: null, picking: null });
    onChange(null);
  };
  const onPickerKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    if (event.key !== 'Escape' || picking === null) return;
    // Handled here rather than by `useDismiss` so focus can go back to the field;
    // stopped so the hook's document listener does not close it a second time.
    event.stopPropagation();
    refocus.current = picking;
    close();
  };

  return (
    <div className="tb-dates" ref={rootRef}>
      <div className="tb-dates-picker" ref={pickerRef} onKeyDown={onPickerKeyDown}>
        <div className="tb-dates-fields">
          <DateField
            which="start"
            label={filterCopy.startDate}
            value={start}
            picking={picking}
            calendarId={calendarId}
            buttonRef={fieldRefs.start}
            onToggle={toggle}
          />
          <DateField
            which="end"
            label={filterCopy.endDate}
            value={end}
            picking={picking}
            calendarId={calendarId}
            buttonRef={fieldRefs.end}
            onToggle={toggle}
          />
        </div>
        {picking !== null ? (
          <OpenCalendar
            id={calendarId}
            picking={picking}
            dismissRef={pickerRef}
            scrollRef={rootRef}
            onDismiss={close}
            initialMonth={(picking === 'end' ? (end ?? start) : (start ?? end)) ?? today}
            today={today}
            start={start}
            end={end}
            onPick={pick}
          />
        ) : null}
      </div>
      <div className="tb-dates-actions">
        <span className="tb-dates-hint" aria-live="polite">
          {draftHint(draft)}
        </span>
        {start !== null || end !== null ? (
          <Button variant="tertiary" size="sm" onClick={clear}>
            {filterCopy.clearDates}
          </Button>
        ) : null}
      </div>
    </div>
  );
}

interface DateFieldProps {
  which: DateEnd;
  label: string;
  value: string | null;
  picking: DateEnd | null;
  calendarId: string;
  buttonRef: RefObject<HTMLButtonElement | null>;
  onToggle: (which: DateEnd) => void;
}

/** A labelled field that shows one end of the window and opens the calendar to set it. */
function DateField({ which, label, value, picking, calendarId, buttonRef, onToggle }: DateFieldProps) {
  const id = useId();
  const open = picking === which;
  const shown = value === null ? null : formatDateShort(value);
  return (
    <div className="tb-dates-field">
      <label htmlFor={id}>{label}</label>
      <button
        type="button"
        id={id}
        ref={buttonRef}
        className={open ? 'tb-dates-field-box tb-dates-field-open' : 'tb-dates-field-box'}
        aria-label={filterCopy.dateField(label, shown)}
        aria-expanded={open}
        aria-controls={open ? calendarId : undefined}
        onClick={() => onToggle(which)}
      >
        <span className={shown === null ? 'tb-dates-unset' : undefined}>{shown ?? filterCopy.noDate}</span>
        <Icon name="calendar" aria-hidden="true" />
      </button>
    </div>
  );
}

interface OpenCalendarProps {
  id: string;
  picking: DateEnd;
  /** Clicks inside this element (the fields and the calendar) do not close it. */
  dismissRef: RefObject<HTMLDivElement | null>;
  /** The fields and calendar, brought into view as the calendar opens. */
  scrollRef: RefObject<HTMLDivElement | null>;
  onDismiss: () => void;
  initialMonth: string;
  today: string;
  start: string | null;
  end: string | null;
  onPick: (day: string) => void;
}

/**
 * The calendar, mounted only while a field is selected, so its outside-click
 * listener and its scroll into view exist exactly as long as it is open.
 */
function OpenCalendar({ id, picking, dismissRef, scrollRef, onDismiss, initialMonth, today, start, end, onPick }: OpenCalendarProps) {
  useDismiss(dismissRef, onDismiss);

  // The panel above the map is height-capped and scrolls, and the filters sit
  // above the dates, so the calendar can open below the fold. Bring the fields
  // and the calendar into view as it opens. Optional call: jsdom has no
  // `scrollIntoView`.
  useEffect(() => {
    scrollRef.current?.scrollIntoView?.({ block: 'nearest' });
  }, [scrollRef]);

  return (
    <div className={picking === 'end' ? 'tb-dates-calendar tb-dates-calendar-end' : 'tb-dates-calendar'} id={id}>
      <MonthCalendar
        initialMonth={initialMonth}
        today={today}
        min={today}
        selectedStart={start}
        selectedEnd={end}
        onPick={onPick}
      />
    </div>
  );
}
