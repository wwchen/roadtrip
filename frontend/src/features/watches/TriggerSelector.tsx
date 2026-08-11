import { useState } from 'react';
import { SeededTextField, Toggle } from '@ui';
import type { TriggerState } from '@/lib/watch-triggers';

export interface TriggerSelectorProps {
  value: TriggerState;
  onChange: (next: TriggerState) => void;
  disabled?: boolean;
}

const checkedOf = (e: Event): boolean => (e.target as HTMLInputElement).checked;
const valueOf = (e: Event): string => (e.target as HTMLInputElement).value;

/**
 * LDS controls are uncontrolled: seed toggles once and mirror changes into state.
 * The conditional channel field uses SeededTextField so remounting it reads the
 * current value rather than the form's initial snapshot.
 *
 * Every control carries an `id`, and each Toggle also an `aria-label`: LDS's
 * `textField` emits `<label for={id}>`, but its `toggle` puts the visible label in
 * a `<span>` beside the switch, so `id` alone leaves the checkbox unnamed.
 */
export function TriggerSelector({ value, onChange, disabled }: TriggerSelectorProps) {
  // Freeze toggle seeds so their DOM is not replaced during editing.
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
        <SeededTextField
          id="watch-slack-channel"
          name="slack_channel"
          label="Channel"
          type="text"
          placeholder="#alerts"
          seed={value.slackChannel}
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
        help="Send to the email address saved in your account settings."
        defaultChecked={initial.emailNotify}
        disabled={disabled}
        onChange={(e) => patch({ emailNotify: checkedOf(e) })}
      />
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
