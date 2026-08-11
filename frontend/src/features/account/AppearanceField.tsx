import { SegmentedControl } from '@ui';
import { THEME_CHOICES, type ThemeChoice } from '@/lib/theme';
import './account.css';

const LABELS: Readonly<Record<ThemeChoice, string>> = {
  light: 'Light',
  dark: 'Dark',
  system: 'System',
};

const FIELD_LABEL = 'Appearance';
const HELP_TEXT = 'System follows your device setting.';
const FIELD_NAME = 'theme';

const OPTIONS = THEME_CHOICES.map((value) => ({ value, label: LABELS[value] }));

export interface AppearanceFieldProps {
  value: ThemeChoice;
  onChange: (choice: ThemeChoice) => void;
}

/**
 * The theme picker, as a form field.
 *
 * Three states in one control rather than a switch: a switch carries two, and
 * keeping `system` would cost a second switch that disables the first.
 *
 * **`SegmentedControl` has no visible label and no help slot.** Confirmed against
 * `@lew-ds/lds/src/templates/segmented-control.js`: the vanilla template turns
 * `label` into `aria-label` on the `role="radiogroup"` wrapper and renders
 * nothing else — unlike `TextField`/`Toggle`, whose `label`/`help` route through
 * the slot-portal mechanism (`lds-react`'s `controllers.jsx` only does that for
 * `CodeField`/`Textarea`, not `SegmentedControl`). So the visible label and help
 * text below are plain markup, reusing the same label/help typography
 * `SecretField` already defined for the same reason (`rt-account-row-label`,
 * `rt-secret-field-help`) rather than adding a duplicate pair of rules — the
 * `aria-label` still carries the group's accessible name for assistive tech.
 */
export function AppearanceField({ value, onChange }: AppearanceFieldProps) {
  return (
    <div className="rt-appearance-field">
      <span className="rt-account-row-label">{FIELD_LABEL}</span>
      <SegmentedControl
        name={FIELD_NAME}
        label={FIELD_LABEL}
        options={OPTIONS}
        value={value}
        onChange={(next) => onChange(next as ThemeChoice)}
      />
      <span className="rt-secret-field-help">{HELP_TEXT}</span>
    </div>
  );
}
