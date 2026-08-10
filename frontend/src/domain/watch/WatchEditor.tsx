// The trigger form for one availability watch.
//
// Port of web/availability/watch-editor.js. Two things about that module are worth
// knowing, because both change here:
//
//   - It injected its own ~150 lines of CSS into `document.head` from JS, guarded by
//     an id check. That is gone: the rules live in `availability.css` with everything
//     else, which is also how they become subject to the colour guardrail.
//   - Its trigger state was a hand-rolled `{slackNotify, emailNotify, addToCart, …}`
//     object with its own payload builder. `lib/watch-triggers.ts` already holds that
//     shape and its `buildTriggerPayload`, ported in Phase 1 for the watches page, so
//     this form uses it rather than restating the mapping. Both surfaces emit the same
//     payload by construction now, which is what the vanilla comment said it wanted.
//
// The capability gates are honoured as the original had them: a trigger the provider
// does not support is hidden, *unless* the watch being edited already uses it — an
// existing watch must never be silently stripped of a trigger by opening its editor.
import { useState } from 'react';
import { HttpError } from '@/api/http';
import type { Watch } from '@/api/watches-api';
import {
  TRIGGER_KIND_ATC,
  TRIGGER_KIND_EMAIL_NOTIFY,
  TRIGGER_KIND_SLACK_NOTIFY,
  buildTriggerPayload,
  triggerStateOf,
  type TriggerPayload,
  type TriggerState,
} from '@/lib/watch-triggers';
import type { WatchCapabilities } from '@/lib/watch-windows';

export interface WatchEditorProps {
  title?: string;
  subtitle?: string;
  /** The watch being edited, or null to create one. */
  watch: Watch | Partial<Watch> | null;
  capabilities: WatchCapabilities;
  onSave: (payload: TriggerPayload) => Promise<void>;
  /** Omitted when there is nothing to remove. */
  onRemove?: (() => Promise<void>) | null;
  onClose?: () => void;
}

export function WatchEditor({
  title,
  subtitle,
  watch,
  capabilities,
  onSave,
  onRemove,
  onClose,
}: WatchEditorProps) {
  const [state, setState] = useState<TriggerState>(() => initialState(watch, capabilities));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const patch = (changes: Partial<TriggerState>): void => {
    setState((current) => ({ ...current, ...changes }));
    setError(null);
  };

  const submit = async (): Promise<void> => {
    const payload = buildTriggerPayload(state);
    // Validated here rather than server-side alone: a watch with no triggers is
    // accepted by the API and then silently never tells anyone anything.
    if (payload.trigger_kinds.length === 0) {
      setError('Select at least one trigger.');
      return;
    }
    await run(() => onSave(payload));
  };

  const run = async (action: () => Promise<void>): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      await action();
      // Deliberately does not clear `busy`: a successful save closes the popover,
      // and re-enabling the buttons first lets a double-tap fire twice.
    } catch (caught) {
      if ((caught as Error)?.name === 'AbortError') return;
      setBusy(false);
      setError(saveErrorMessage(caught));
    }
  };

  // A capability the *existing* watch already uses stays visible even when the
  // provider no longer advertises it, so saving cannot quietly drop a trigger.
  const canSlack = capabilities.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY) || state.slackNotify;
  const canEmail = capabilities.triggerKinds.has(TRIGGER_KIND_EMAIL_NOTIFY) || state.emailNotify;
  const canAtc = capabilities.triggerKinds.has(TRIGGER_KIND_ATC);

  return (
    <div className="rt-watch-editor" role="group" aria-label="Availability watch editor">
      <div className="rt-watch-editor-head">
        <div>
          {title ? <div className="rt-watch-editor-title">{title}</div> : null}
          {subtitle ? <div className="rt-watch-editor-subtitle">{subtitle}</div> : null}
        </div>
        {onClose ? (
          <button
            type="button"
            className="rt-watch-editor-icon"
            aria-label="Close"
            onClick={onClose}
          >
            ×
          </button>
        ) : null}
      </div>

      <div className="rt-watch-editor-body">
        {canSlack ? (
          <ToggleRow
            name="slack_notify"
            title="Slack"
            help="Post when a matching site opens."
            checked={state.slackNotify}
            disabled={busy}
            onChange={(slackNotify) => patch({ slackNotify })}
          />
        ) : null}

        {canEmail ? (
          <ToggleRow
            name="email_notify"
            title="Email"
            help="Send to the email address saved in your account settings."
            checked={state.emailNotify}
            disabled={busy}
            onChange={(emailNotify) => patch({ emailNotify })}
          />
        ) : null}

        {canAtc || state.addToCart ? (
          <ToggleRow
            name="atc"
            title="Add to cart"
            help={canAtc ? 'Try to hold a matching site.' : 'Unavailable for this watch scope.'}
            checked={state.addToCart}
            // Enabled even when the provider no longer supports it, so a watch that
            // already has it set can be turned OFF. The original wrote
            // `busy || (!canAtc && !state.addToCart)` here, which cannot fire: that
            // second clause is exactly the case where the row is not rendered at all.
            disabled={busy}
            onChange={(addToCart) => patch({ addToCart })}
          />
        ) : null}

        <ToggleRow
          name="stop_when_triggered"
          title="Stop when triggered"
          help="Mark done after a successful trigger."
          checked={state.stopWhenTriggered}
          disabled={busy}
          onChange={(stopWhenTriggered) => patch({ stopWhenTriggered })}
        />
      </div>

      {error ? (
        <div className="rt-watch-editor-error" role="alert">
          {error}
        </div>
      ) : null}

      <div className="rt-watch-editor-actions">
        {onRemove ? (
          <button
            type="button"
            className="rt-watch-editor-remove"
            disabled={busy}
            onClick={() => void run(onRemove)}
          >
            Remove
          </button>
        ) : null}
        <button
          type="button"
          className="rt-watch-editor-save"
          disabled={busy}
          onClick={() => void submit()}
        >
          {watch ? 'Save' : 'Set watch'}
        </button>
      </div>
    </div>
  );
}

interface ToggleRowProps {
  name: string;
  title: string;
  help: string;
  checked: boolean;
  disabled: boolean;
  onChange: (checked: boolean) => void;
}

/**
 * A labelled switch.
 *
 * A plain controlled checkbox with a styled track, not an LDS control: LDS's form
 * components are uncontrolled by design (`value` is the initial value only), and
 * these toggles drive conditional fields — flipping Email has to reveal the address
 * input in the same render.
 */
function ToggleRow({ name, title, help, checked, disabled, onChange }: ToggleRowProps) {
  return (
    <label className="rt-watch-editor-toggle">
      <span className="rt-watch-editor-toggle-text">
        <span className="rt-watch-editor-toggle-title">{title}</span>
        <span className="rt-watch-editor-toggle-help">{help}</span>
      </span>
      <span className="rt-watch-editor-switch">
        <input
          type="checkbox"
          name={name}
          checked={checked}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
        />
        <span className="rt-watch-editor-switch-track" aria-hidden="true" />
      </span>
    </label>
  );
}

/**
 * The form's opening state.
 *
 * Editing an existing watch reflects it exactly. Creating one opens with a channel
 * already ticked, because a watch with no trigger is the one configuration that
 * cannot work — but only a channel the provider actually has, since pre-ticking one
 * it cannot use would just fail on save. Slack is preferred when both are possible:
 * it needs no further input, where email needs an address.
 *
 * Email carries that default on a provider with no Slack, which is how the day
 * panel's "Set watch" reaches a working form there: it opens this editor, and an
 * editor whose single toggle starts off can only fail its own "select at least one
 * trigger" check. `triggerStateOf` defaults Slack on unconditionally for the watches
 * page, which has no per-provider capabilities to consult; this narrows that default
 * rather than restating the rest of the state.
 */
function initialState(
  watch: Watch | Partial<Watch> | null,
  capabilities: WatchCapabilities,
): TriggerState {
  const state = triggerStateOf(watch);
  if (watch) return state;
  const slackNotify = capabilities.triggerKinds.has(TRIGGER_KIND_SLACK_NOTIFY);
  const emailNotify = !slackNotify && capabilities.triggerKinds.has(TRIGGER_KIND_EMAIL_NOTIFY);
  return { ...state, slackNotify, emailNotify };
}

/**
 * Turn a save failure into copy.
 *
 * The recognised cases are the ones a user can act on; everything else is "try
 * again", because a raw server message here is noise in a 240px popover.
 */
function saveErrorMessage(caught: unknown): string {
  // Deliberately says nothing about an expired session, even though that is a failure
  // mode here: a 401 withdraws every watch affordance, which unmounts the cell this
  // popover is anchored to and therefore closes the popover. A message in here would
  // be raised into a component that is about to disappear. `AvailabilityWeek` raises a
  // toast instead — one surface, and one that outlives the popover.
  const body = caught instanceof HttpError && typeof caught.body === 'string' ? caught.body : '';
  if (body.includes('unsupported_trigger')) return 'Add to cart is not available for this watch.';
  if (body.includes('invalid_trigger_config')) return 'Check the trigger settings and try again.';
  return 'Could not save. Try again.';
}
