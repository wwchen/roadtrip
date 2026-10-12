// Start and end date entry for the Check availability step. M2 stores the window
// only; M3 polls with it.
//
// One calendar picks both ends (`nextDraft`): the first click is the start, the
// next later click is the end, and a click on or before the start — or any click
// once both are set — starts over. Every window it can produce is two real dates
// in order, so Save checks only that both ends are there.
//
// A calendar rather than `type="date"` fields: `@lew-ds/lds-react`'s change
// wiring (`useChangeHandler` in `runtime.jsx`) fires on native `input` events only
// for a fixed `TEXT_LIKE` set of input types, and `date` is not in it — so an LDS
// `TextField type="date"` never calls `onChange` at all.
import { useState } from 'react';
import { Button, MonthCalendar } from '@ui';
import { localToday, localYmd } from '@/lib/local-date';
import { filterCopy } from '@/lib/strings';
import type { DateWindow } from '@/stores/campgroundFilterStore';

const MS_PER_DAY = 86_400_000;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

const shortDate = new Intl.DateTimeFormat('en-US', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });

/** Check if a string is in ISO date format and a valid date. */
export function isIsoDate(value: string): boolean {
  return ISO_DATE.test(value) && !Number.isNaN(Date.parse(value));
}

/** Whole nights between two ISO dates; 0 or less means the window is not valid. */
export function nightsBetween(start: string, end: string): number {
  return Math.round((Date.parse(end) - Date.parse(start)) / MS_PER_DAY);
}

/**
 * "Fri Sep 11" from "2026-09-11"; UTC so the calendar date never shifts.
 * Returns input unchanged if not a valid ISO date to prevent throwing.
 * `en-US`'s weekday+month+day format inserts a comma after the weekday
 * ("Fri, Sep 11") — stripped here since the wanted form has none.
 */
export function formatDateShort(iso: string): string {
  if (!isIsoDate(iso)) return iso;
  return shortDate.format(new Date(`${iso}T00:00:00Z`)).replace(',', '');
}

/** A window part-way through picking: either end may still be missing. */
export interface DateDraft {
  start: string | null;
  end: string | null;
}

/** The draft after a click on `day`; the file comment has the rule. */
export function nextDraft(draft: DateDraft, day: string): DateDraft {
  if (draft.start === null || draft.end !== null || day <= draft.start) return { start: day, end: null };
  return { start: draft.start, end: day };
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
  const [draft, setDraft] = useState<DateDraft>({ start: initial?.start ?? null, end: initial?.end ?? null });
  const [today] = useState(() => localYmd(localToday()));
  const { start, end } = draft;
  return (
    <div className="tb-dates">
      <dl className="tb-dates-ends">
        <DateEnd label={filterCopy.startDate} value={start} />
        <DateEnd label={filterCopy.endDate} value={end} />
      </dl>
      <div className="tb-dates-calendar">
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
        <span className="tb-dates-hint">{draftHint(draft)}</span>
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

function DateEnd({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="tb-dates-end">
      <dt>{label}</dt>
      <dd className={value === null ? 'tb-dates-unset' : undefined}>{value === null ? filterCopy.noDate : formatDateShort(value)}</dd>
    </div>
  );
}
