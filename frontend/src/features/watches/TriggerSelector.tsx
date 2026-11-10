import { TextField, Toggle } from '@ui';
import type { TriggerState } from '@/lib/watch-triggers';

export interface TriggerSelectorProps {
  value: TriggerState;
  onChange: (next: TriggerState) => void;
  disabled?: boolean;
}

/**
 * Every control carries an `id`, and each Toggle also an `aria-label`: LDS's
 * `textField` emits `<label for={id}>`, but its `toggle` puts the visible label in
 * a `<span>` beside the switch, so `id` alone leaves the checkbox unnamed.
 */
export function TriggerSelector({ value, onChange, disabled }: TriggerSelectorProps) {
  const patch = (fields: Partial<TriggerState>) => onChange({ ...value, ...fields });

  return (
    <div className="rt-trigger-selector">
      <Toggle
        id="watch-slack-notify"
        name="slack_notify"
        label="Slack"
        aria-label="Slack"
        help="Post when a matching site opens."
        checked={value.slackNotify}
        disabled={disabled}
        onChange={(e) => patch({ slackNotify: e.target.checked })}
      />
      {value.slackNotify && (
        <TextField
          id="watch-slack-channel"
          name="slack_channel"
          label="Channel"
          type="text"
          placeholder="#alerts"
          value={value.slackChannel}
          disabled={disabled}
          help="Leave blank to use the channel saved in your account settings."
          onChange={(e) => patch({ slackChannel: e.target.value })}
        />
      )}

      <Toggle
        id="watch-email-notify"
        name="email_notify"
        label="Email"
        aria-label="Email"
        help="Send to the email address saved in your account settings."
        checked={value.emailNotify}
        disabled={disabled}
        onChange={(e) => patch({ emailNotify: e.target.checked })}
      />
      <Toggle
        id="watch-stop-when-triggered"
        name="stop_when_triggered"
        label="Stop when triggered"
        aria-label="Stop when triggered"
        help="Mark done after a successful trigger."
        checked={value.stopWhenTriggered}
        disabled={disabled}
        onChange={(e) => patch({ stopWhenTriggered: e.target.checked })}
      />
    </div>
  );
}
