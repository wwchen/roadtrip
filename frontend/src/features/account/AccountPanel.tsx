import { ConfirmButton } from '@ui';
import type { SettingsResponse } from '@/api/account-api';
import './account.css';

export interface AccountPanelProps {
  settings: SettingsResponse;
  onSignOut: () => void;
  onDisconnectSlack: () => void;
}

/** Identity summary and irreversible account actions. */
export function AccountPanel({ settings, onSignOut, onDisconnectSlack }: AccountPanelProps) {
  // Gated on a token actually being stored: offering to disconnect nothing reads
  // as a broken button.
  const slackConfigured = settings.notifications.slack_configured;

  return (
    <div className="rt-account-panel">
      <div className="rt-account-row">
        <span className="rt-account-row-label">Signed in as</span>
        <span className="rt-account-row-value">{settings.profile.login_email}</span>
      </div>

      <ConfirmButton
        variant="secondary"
        label="Sign out"
        confirmLabel="Confirm sign out"
        onConfirm={onSignOut}
      />

      {slackConfigured && (
        <section className="rt-account-danger">
          <h3 className="rt-account-danger-title">Danger zone</h3>
          <ConfirmButton
            variant="tertiary"
            hue="red"
            label="Disconnect Slack"
            confirmLabel="Confirm disconnect"
            onConfirm={onDisconnectSlack}
          />
        </section>
      )}
    </div>
  );
}
