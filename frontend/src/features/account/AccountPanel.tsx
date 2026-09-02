import { ConfirmButton } from '@ui';
import type { SettingsResponse } from '@/api/account-api';
import './account.css';

export interface AccountPanelProps {
  settings: SettingsResponse;
  onSignOut: () => void;
  onDisconnectSlack: () => void;
  onRemoveRecgov: () => void;
}

/**
 * Identity summary and irreversible account actions.
 *
 * Credential removal lives here rather than in the panel that edits the
 * credential: Account is where destructive account actions already are, and a
 * delete button inside an editable form is the one that gets hit by accident.
 */
export function AccountPanel({
  settings,
  onSignOut,
  onDisconnectSlack,
  onRemoveRecgov,
}: AccountPanelProps) {
  // Gated on a token actually being stored: offering to disconnect nothing reads
  // as a broken button.
  const slackConfigured = settings.notifications.slack_configured;
  const recgovConfigured = settings.booking.recgov_configured;

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

      {(slackConfigured || recgovConfigured) && (
        <section className="rt-account-danger">
          <h3 className="rt-account-danger-title">Danger zone</h3>
          {slackConfigured && (
            <ConfirmButton
              variant="tertiary"
              hue="red"
              label="Disconnect Slack"
              confirmLabel="Confirm disconnect"
              onConfirm={onDisconnectSlack}
            />
          )}
          {recgovConfigured && (
            <ConfirmButton
              variant="tertiary"
              hue="red"
              label="Remove rec.gov credentials"
              confirmLabel="Confirm removal"
              onConfirm={onRemoveRecgov}
            />
          )}
        </section>
      )}
    </div>
  );
}
