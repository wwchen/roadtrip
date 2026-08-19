import { AppearanceField } from './AppearanceField';
import { useThemeStore } from '@/stores/themeStore';
import type { ProfileValues } from './ProfilePanel';
import './account.css';

export interface AppearancePanelProps {
  values: ProfileValues;
  onChange: (values: ProfileValues) => void;
}

/**
 * The Appearance rail section.
 *
 * Theme is part of the profile document, not a device preference, so this edits
 * the same `ProfileValues` the Profile section does and commits through the same
 * Save button — it is only a separate *section*, not a separate save. Picking a
 * choice previews it immediately; `SettingsModal` reverts an unsaved preview on
 * close.
 */
export function AppearancePanel({ values, onChange }: AppearancePanelProps) {
  return (
    <div className="rt-account-panel">
      <AppearanceField
        value={values.theme}
        onChange={(theme) => {
          // Preview, don't persist: Save commits it, SettingsModal reverts it.
          useThemeStore.getState().previewChoice(theme);
          onChange({ ...values, theme });
        }}
      />
    </div>
  );
}
