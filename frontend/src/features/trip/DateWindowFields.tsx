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

const shortDate = new Intl.DateTimeFormat('en-US', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });

/** Whole nights between two ISO dates; 0 or less means the window is not valid. */
export function nightsBetween(start: string, end: string): number {
  return Math.round((Date.parse(end) - Date.parse(start)) / MS_PER_DAY);
}

/**
 * "Fri Sep 11" from "2026-09-11"; UTC so the calendar date never shifts.
 * `en-US`'s weekday+month+day format inserts a comma after the weekday
 * ("Fri, Sep 11") — stripped here since the wanted form has none.
 */
export function formatDateShort(iso: string): string {
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
  const inverted = filled && nightsBetween(start, end) <= 0;
  return (
    <div className="tb-dates">
      <div className="tb-dates-fields">
        <SeededTextField id={START_ID} name="start_date" type="text" label={filterCopy.startDate} seed={start} onChange={(e) => setStart(valueOf(e))} />
        <SeededTextField id={END_ID} name="end_date" type="text" label={filterCopy.endDate} seed={end} onChange={(e) => setEnd(valueOf(e))} />
      </div>
      {inverted ? <span className="tb-dates-error">{filterCopy.endBeforeStart}</span> : null}
      <div className="tb-dates-actions">
        <Button variant="tertiary" size="sm" onClick={onCancel}>
          {filterCopy.cancelDates}
        </Button>
        <Button variant="primary" size="sm" disabled={!filled || inverted} onClick={() => onSave({ start, end })}>
          {filterCopy.saveDates}
        </Button>
      </div>
    </div>
  );
}
