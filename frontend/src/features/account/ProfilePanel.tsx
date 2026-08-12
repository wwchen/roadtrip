import { SeededTextField } from '@ui';
import { coerceChoice, type ThemeChoice } from '@/lib/theme';
import { useThemeStore } from '@/stores/themeStore';
import { AppearanceField } from './AppearanceField';
import type { Profile, SettingsResponse } from '@/api/account-api';
import './account.css';

/** The fields this panel can edit. */
export interface ProfileValues {
  display_name: string;
  theme: ThemeChoice;
}

/** The saved values, as this panel's editable shape. */
export function profileValuesOf(settings: SettingsResponse): ProfileValues {
  return {
    display_name: settings.profile.display_name || '',
    theme: coerceChoice(settings.profile.theme),
  };
}

/** True when the edited values differ from what is saved. */
export function isProfileDirty(settings: SettingsResponse, values: ProfileValues): boolean {
  return (
    values.display_name !== (settings.profile.display_name || '') ||
    values.theme !== coerceChoice(settings.profile.theme)
  );
}

export function buildProfilePayload(
  values: ProfileValues,
): { display_name: string; theme: ThemeChoice } {
  return { display_name: values.display_name, theme: values.theme };
}

export interface ProfilePanelProps {
  profile: Profile;
  values: ProfileValues;
  onChange: (values: ProfileValues) => void;
}

/**
 * Rebuild of web/account/profile-panel.js.
 *
 * Editable display name and appearance, read-only login email with a verified
 * badge.
 *
 * **The values live in the parent**, which is the one structural change from the
 * original. That mounted a FormSection, kept the value in the DOM, and exposed
 * `getPayload()`/`isDirty()` for the modal to pull from on save. Here the modal owns
 * the state and passes it down, so "is anything unsaved" is derivable at any moment
 * rather than only when someone asks — which is what the modal's discard guard
 * needs.
 *
 * The field is uncontrolled per LDS (see the plan's gotchas); reseeding it means
 * remounting the panel, which the modal does by keying on the loaded settings.
 */
export function ProfilePanel({ profile, values, onChange }: ProfilePanelProps) {
  return (
    <div className="rt-account-panel">
      <SeededTextField
        id="settings-display-name"
        name="display_name"
        label="Display name"
        type="text"
        placeholder="Your name"
        seed={values.display_name}
        onChange={(e) =>
          onChange({ ...values, display_name: (e.target as HTMLInputElement).value })
        }
      />

      <AppearanceField
        value={values.theme}
        onChange={(theme) => {
          // Preview, don't persist: Save commits it, SettingsModal reverts it.
          useThemeStore.getState().previewChoice(theme);
          onChange({ ...values, theme });
        }}
      />

      <div className="rt-account-row">
        <span className="rt-account-row-label">Login email</span>
        <span className="rt-account-row-value">
          {profile.login_email}
          {/* The badge is an assertion about the address, so it sits with it rather
              than in its own column. */}
          {profile.is_email_verified && (
            <span className="rt-account-verified" aria-label="Verified">
              ✓ verified
            </span>
          )}
        </span>
      </div>
    </div>
  );
}
