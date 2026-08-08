import { useState } from 'react';
import { Banner, Button, TextField } from '@ui';
import type { CreateWatchRequest, UpdateWatchRequest, Watch } from '@/api/watches-api';
import { buildTriggerPayload, triggerStateOf, type TriggerState } from '@/lib/watch-triggers';
import { TriggerSelector } from './TriggerSelector';

export type WatchFormMode = 'create' | 'edit';

/** Deep-link prefill (`?action=create&poi_id=…&start_date=…`). */
export interface WatchFormPrefill {
  poi_id?: string;
  start_date?: string;
}

export interface WatchFormSubmit {
  id: number | null;
  body: CreateWatchRequest | UpdateWatchRequest;
}

export interface WatchFormProps {
  mode: WatchFormMode;
  /** The watch being edited; null in create mode. */
  watch?: Watch | null;
  prefill?: WatchFormPrefill | null;
  /** True while a save is in flight. Disables the buttons, not the fields. */
  loading?: boolean;
  error?: string | null;
  onSubmit: (submission: WatchFormSubmit) => void;
  onCancel?: () => void;
}

interface FormFields {
  poiId: string;
  startDate: string;
  endDate: string;
}

function fieldsFor(
  watch: Watch | null | undefined,
  prefill: WatchFormPrefill | null | undefined,
): FormFields {
  if (watch) {
    return {
      poiId: watch.poi_id != null ? String(watch.poi_id) : '',
      startDate: watch.start_date || '',
      endDate: watch.end_date || '',
    };
  }
  return {
    poiId: prefill?.poi_id ?? '',
    startDate: prefill?.start_date ?? '',
    endDate: '',
  };
}

const valueOf = (e: Event): string => (e.target as HTMLInputElement).value;

/**
 * The typed POI id as a number, or null when it is blank or not a number.
 *
 * The vanilla form posted the raw input string (`poi_id: "42"`), which only
 * worked because the backend's JSON parsing is lenient. A number always
 * satisfies the DTO's `Long?`, so this sends one; a non-numeric entry becomes
 * null and the backend answers with its "target required" validation error
 * rather than a parse failure.
 */
function parsePoiId(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}

/**
 * Rebuild of web/watches/watch-form.js on LDS.
 *
 * **Uncontrolled by necessity.** LDS's form controls take their `value` at first
 * render only; a changed prop re-renders the template and swaps the input's DOM,
 * which during typing eats the caret and every keystroke after the first. So the
 * fields are seeded once with `defaultValue`, the DOM owns the live value, and
 * `onChange` mirrors it into React state for the payload. To reseed — switching
 * between create and edit, or clearing after a save — the PARENT remounts this
 * component with a new `key`; there is deliberately no reseeding effect here.
 *
 * Behaviors carried over from the original:
 *  - POI id, start date, and end date are all read-only in edit mode. Moving a
 *    watch's target would orphan its poller, and the update request does not
 *    accept a new poi_id at all. The dates are still sent, from their locked
 *    values, so the payload matches what the vanilla form posted.
 *  - Saving a `done` watch reactivates it, because the only reason to edit a
 *    finished watch is to run it again.
 *
 * One deliberate improvement: `loading` disables the buttons but NOT the fields.
 * The vanilla form disabled everything, which here would swap the inputs' DOM
 * mid-save and discard what the user typed the moment a save failed.
 */
export function WatchForm({
  mode,
  watch,
  prefill,
  loading = false,
  error = null,
  onSubmit,
  onCancel,
}: WatchFormProps) {
  const isEdit = mode === 'edit';
  const [initialFields] = useState(() => fieldsFor(watch, prefill));
  const [fields, setFields] = useState<FormFields>(initialFields);
  const [triggers, setTriggers] = useState<TriggerState>(() => triggerStateOf(watch));

  const handleSubmit = () => {
    const triggerPayload = buildTriggerPayload(triggers);
    if (isEdit && watch) {
      const body: UpdateWatchRequest = {
        start_date: fields.startDate,
        end_date: fields.endDate,
        ...triggerPayload,
      };
      // Editing a finished watch is a request to run it again.
      if (watch.status === 'done') body.status = 'active';
      onSubmit({ id: watch.id, body });
      return;
    }
    onSubmit({
      id: null,
      body: {
        poi_id: parsePoiId(fields.poiId),
        start_date: fields.startDate,
        end_date: fields.endDate,
        ...triggerPayload,
      },
    });
  };

  return (
    <section className="rt-watch-form" aria-label={isEdit ? 'Edit watch' : 'Create watch'}>
      <h2 className="rt-watch-form-title">{isEdit ? 'Edit Watch' : 'Create Watch'}</h2>

      {error && (
        <Banner status="error" role="alert">
          {error}
        </Banner>
      )}

      <div className="rt-watch-form-fields">
        <TextField
          id="watch-poi-id"
          name="poi_id"
          label="POI ID"
          placeholder="e.g. 42"
          defaultValue={initialFields.poiId}
          disabled={isEdit}
          help={isEdit ? "A watch's target cannot be moved." : undefined}
          onChange={(e) => setFields((f) => ({ ...f, poiId: valueOf(e) }))}
        />
        <TextField
          id="watch-start-date"
          name="start_date"
          label="Start date"
          type="date"
          defaultValue={initialFields.startDate}
          disabled={isEdit}
          onChange={(e) => setFields((f) => ({ ...f, startDate: valueOf(e) }))}
        />
        <TextField
          id="watch-end-date"
          name="end_date"
          label="End date"
          type="date"
          defaultValue={initialFields.endDate}
          disabled={isEdit}
          onChange={(e) => setFields((f) => ({ ...f, endDate: valueOf(e) }))}
        />
      </div>

      <div className="rt-watch-form-triggers">
        <TriggerSelector value={triggers} onChange={setTriggers} />
      </div>

      <div className="rt-watch-form-actions">
        {isEdit && (
          <Button variant="secondary" disabled={loading} onClick={() => onCancel?.()}>
            Cancel
          </Button>
        )}
        <Button variant="primary" disabled={loading} onClick={handleSubmit}>
          {isEdit ? 'Save' : 'Create'}
        </Button>
      </div>
    </section>
  );
}
