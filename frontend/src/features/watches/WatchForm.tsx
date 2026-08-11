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

/** Parse the optional POI id into the backend DTO's numeric shape. */
function parsePoiId(raw: string): number | null {
  const trimmed = raw.trim();
  if (trimmed === '') return null;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}

// LDS fields are seeded once and reseeded by a parent key. Edit mode locks target
// fields, saving a done watch reactivates it, and loading disables buttons only so
// a failed save cannot discard typed values.
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
