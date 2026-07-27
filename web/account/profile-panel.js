// web/account/profile-panel.js
//
// Mounts the Profile settings panel: editable display name, read-only login
// email with verified badge.
//
// DS mounts are injectable (via config._mount*) so tests can pass fakes
// without real DOM interaction.

import { mountFormSection as _defaultMountFormSection } from '../design-system/form-section.js';
import { profilePanelTemplate } from './profile-panel-template.js';

const STYLE_ID = 'rt-profile-panel-styles';

// ── Pure helpers ─────────────────────────────────────────────────────────────

/**
 * Compute whether the current profile field values differ from initial settings.
 * @param {{ profile: { display_name: string } }} settings
 * @param {{ display_name: string }} values
 * @returns {boolean}
 */
export function computeProfileDirty(settings, values) {
  return values.display_name !== (settings.profile.display_name || '');
}

/**
 * Build the payload to send when saving profile settings.
 * @param {{ display_name: string }} values
 * @returns {{ display_name: string }}
 */
export function buildProfilePayload(values) {
  return { display_name: values.display_name };
}

// ── Mount ────────────────────────────────────────────────────────────────────

/**
 * @param {Element} container
 * @param {{
 *   settings: import('./settings-types.js').SettingsResponseDto,
 *   onDirtyChange?: (dirty: boolean) => void,
 *   _mountFormSection?: typeof import('../design-system/form-section.js').mountFormSection,
 * }} config
 * @returns {{ getPayload(): { display_name: string }, isDirty(): boolean, dispose(): void }}
 */
export function mountProfilePanel(container, config) {
  const {
    settings,
    onDirtyChange,
    _mountFormSection = _defaultMountFormSection,
  } = config;

  injectStyles();

  const initialDisplayName = settings.profile.display_name || '';
  let currentDirty = false;

  // Render the shell template (contains the static login email row and hosts
  // for the FormSection child).
  container.innerHTML = profilePanelTemplate({
    loginEmail: settings.profile.login_email,
    isEmailVerified: settings.profile.is_email_verified,
  });

  const displayNameHost = container.querySelector('[data-host="display-name"]');

  const displayNameField = _mountFormSection(displayNameHost, {
    label: 'Display name',
    name: 'display_name',
    type: 'text',
    placeholder: 'Your name',
    value: initialDisplayName,
  });

  function checkDirty() {
    const dirty = computeProfileDirty(settings, { display_name: displayNameField.getValue() });
    if (dirty !== currentDirty) {
      currentDirty = dirty;
      onDirtyChange?.(dirty);
    }
  }

  // Listen for input on the container to detect changes inside the FormSection.
  function onInput() {
    checkDirty();
  }

  container.addEventListener('input', onInput);

  return {
    getPayload() {
      return buildProfilePayload({ display_name: displayNameField.getValue() });
    },
    isDirty() {
      return currentDirty;
    },
    dispose() {
      container.removeEventListener('input', onInput);
      displayNameField.dispose();
      container.innerHTML = '';
    },
  };
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/account/profile-panel.css';
  document.head.appendChild(link);
}
