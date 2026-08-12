import { Icon, SeededTextField } from '@ui';
import { coerceChoice, type ThemeChoice } from '@/lib/theme';
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

/** True when the edited display name differs from what is saved. */
export function isDisplayNameDirty(settings: SettingsResponse, values: ProfileValues): boolean {
  return values.display_name !== (settings.profile.display_name || '');
}

/** True when the edited theme differs from what is saved. */
export function isThemeDirty(settings: SettingsResponse, values: ProfileValues): boolean {
  return values.theme !== coerceChoice(settings.profile.theme);
}

/**
 * True when the edited values differ from what is saved.
 *
 * The two slices are also exported separately because Profile and Appearance are
 * their own rail sections but share one payload: each section's Save button gates
 * on its own slice, while `buildProfilePayload` still sends both.
 */
export function isProfileDirty(settings: SettingsResponse, values: ProfileValues): boolean {
  return isDisplayNameDirty(settings, values) || isThemeDirty(settings, values);
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
 * Editable display name, read-only login email with a verified badge.
 *
 * Theme still travels in `ProfileValues` and this panel's payload — it is one
 * profile document server-side — but it is *edited* in `AppearancePanel`, its own
 * rail section, so the two are split by where they read rather than by what they
 * save.
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

      <div className="rt-account-row">
        <span className="rt-account-row-label">Login email</span>
        <span className="rt-account-row-value">
          {profile.login_email}
          {/* The badge is an assertion about the address, so it sits with it rather
              than in its own column. */}
          {profile.is_email_verified && (
            <span className="rt-account-verified" aria-label="Verified">
              <Icon name="check" aria-hidden="true" /> verified
            </span>
          )}
        </span>
      </div>
    </div>
  );
}
