// Start and end date entry for the Check availability step. M2 stores the window
// only; M3 polls with it. The fields are conditionally rendered, so they seed
// through `SeededTextField` and mirror into local state for the Save payload.
//
// `type="text"` rather than `date`: `@lew-ds/lds-react`'s change wiring
// (`useChangeHandler` in `runtime.jsx`) fires on native `input` events only for
// a fixed `TEXT_LIKE` set of input types, and `date` is not in it (nor is it in
// the `change`-based set, which is select/checkbox/radio only) — so a
// `type="date"` field here would never call `onChange` at all. Values are still
// plain `YYYY-MM-DD` strings; only the native date-picker affordance is lost.
import { useState } from 'react';
import { Button, SeededTextField } from '@ui';
import { filterCopy } from '@/lib/strings';
import type { DateWindow } from '@/stores/campgroundFilterStore';

const MS_PER_DAY = 86_400_000;
const START_ID = 'tb-dates-start';
const END_ID = 'tb-dates-end';
const DATE_PLACEHOLDER = 'YYYY-MM-DD';
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

const valueOf = (event: Event): string => (event.target as HTMLInputElement).value;

export interface DateWindowFieldsProps {
  initial: DateWindow | null;
  onSave: (window: DateWindow) => void;
  onCancel: () => void;
}

export function DateWindowFields({ initial, onSave, onCancel }: DateWindowFieldsProps) {
  const [start, setStart] = useState(initial?.start ?? '');
  const [end, setEnd] = useState(initial?.end ?? '');
  const filled = start !== '' && end !== '';
  const startIsIso = isIsoDate(start);
  const endIsIso = isIsoDate(end);
  const validIso = startIsIso && endIsIso;
  const inverted = validIso && nightsBetween(start, end) <= 0;
  const nonIsoError = filled && !validIso;
  return (
    <div className="tb-dates">
      <div className="tb-dates-fields">
        <SeededTextField id={START_ID} name="start_date" type="text" label={filterCopy.startDate} placeholder={DATE_PLACEHOLDER} seed={start} onChange={(e) => setStart(valueOf(e))} />
        <SeededTextField id={END_ID} name="end_date" type="text" label={filterCopy.endDate} placeholder={DATE_PLACEHOLDER} seed={end} onChange={(e) => setEnd(valueOf(e))} />
      </div>
      {inverted ? <span className="tb-dates-error">{filterCopy.endBeforeStart}</span> : null}
      {nonIsoError ? <span className="tb-dates-error">{filterCopy.dateFormat}</span> : null}
      <div className="tb-dates-actions">
        <Button variant="tertiary" size="sm" onClick={onCancel}>
          {filterCopy.cancelDates}
        </Button>
        <Button variant="primary" size="sm" disabled={!filled || !validIso || inverted} onClick={() => onSave({ start, end })}>
          {filterCopy.saveDates}
        </Button>
      </div>
    </div>
  );
}
