import { useState } from 'react';
import { Toggle, TextField } from '@ui';
import type { TriggerState } from '@/lib/watch-triggers';

export interface TriggerSelectorProps {
  value: TriggerState;
  onChange: (next: TriggerState) => void;
  disabled?: boolean;
}

const checkedOf = (e: Event): boolean => (e.target as HTMLInputElement).checked;
const valueOf = (e: Event): string => (e.target as HTMLInputElement).value;

/**
 * Rebuild of web/watches/trigger-selector.js on LDS.
 *
 * The channel and address fields stay conditional on their toggle, as in the
 * original — an address box under a disabled email trigger reads as a required
 * field that does nothing.
 *
 * **LDS form controls are uncontrolled.** Their `value`/`checked` is the initial
 * value only: the components render a template string, so a changed prop swaps
 * the whole field's DOM, and feeding React state back in destroys the focused
 * input on every keystroke (`@lew/lds-react`'s README says so outright, and
 * `attrs.js` maps `defaultValue`/`defaultChecked` onto the `value`/`checked`
 * ATTRIBUTES for exactly this). So each control is seeded once from `initial`,
 * the DOM owns its own value, and `onChange` mirrors it into React state for the
 * conditional fields and the payload. Reseeding is a remount — see WatchForm.
 *
 * Every control carries an `id`, and each Toggle also an `aria-label`: LDS's
 * `textField` emits `<label for={id}>`, but its `toggle` puts the visible label in
 * a `<span>` beside the switch, so `id` alone leaves the checkbox unnamed.
 */
export function TriggerSelector({ value, onChange, disabled }: TriggerSelectorProps) {
  // Captured once, so no LDS control's markup changes while the user is in it.
  const [initial] = useState(value);
  const patch = (fields: Partial<TriggerState>) => onChange({ ...value, ...fields });

  return (
    <div className="rt-trigger-selector">
      <Toggle
        id="watch-slack-notify"
        name="slack_notify"
        label="Slack"
        aria-label="Slack"
        help="Post when a matching site opens."
        defaultChecked={initial.slackNotify}
        disabled={disabled}
        onChange={(e) => patch({ slackNotify: checkedOf(e) })}
      />
      {value.slackNotify && (
        <TextField
          id="watch-slack-channel"
          name="slack_channel"
          label="Channel"
          type="text"
          placeholder="#alerts"
          defaultValue={initial.slackChannel}
          disabled={disabled}
          help="Leave blank to use the channel saved in your account settings."
          onChange={(e) => patch({ slackChannel: valueOf(e) })}
        />
      )}

      <Toggle
        id="watch-email-notify"
        name="email_notify"
        label="Email"
        aria-label="Email"
        help="Send email when a matching site opens."
        defaultChecked={initial.emailNotify}
        disabled={disabled}
        onChange={(e) => patch({ emailNotify: checkedOf(e) })}
      />
      {value.emailNotify && (
        <TextField
          id="watch-email-to"
          name="email_to"
          label="Email address"
          type="text"
          placeholder="you@example.com, other@example.com"
          defaultValue={initial.emailTo}
          disabled={disabled}
          onChange={(e) => patch({ emailTo: valueOf(e) })}
        />
      )}

      <Toggle
        id="watch-stop-when-triggered"
        name="stop_when_triggered"
        label="Stop when triggered"
        aria-label="Stop when triggered"
        help="Mark done after a successful trigger."
        defaultChecked={initial.stopWhenTriggered}
        disabled={disabled}
        onChange={(e) => patch({ stopWhenTriggered: checkedOf(e) })}
      />
    </div>
  );
}
