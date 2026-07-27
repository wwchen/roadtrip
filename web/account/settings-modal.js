// web/account/settings-modal.js
//
// Mounts the full settings modal: tabbed (Profile / Notifications / Account),
// loads settings via `fetchSettings`, wires per-tab dirty/save state.
//
// All collaborators are injectable via config._* so tests can pass fakes
// without real DOM interaction.

import { mountModal as _defaultMountModal } from '../design-system/modal.js';
import { mountTabs as _defaultMountTabs } from '../design-system/tabs.js';
import { mountBanner as _defaultMountBanner } from '../design-system/banner.js';
import { mountProfilePanel as _defaultMountProfilePanel } from './profile-panel.js';
import { mountNotificationsPanel as _defaultMountNotificationsPanel } from './notifications-panel.js';
import { mountAccountPanel as _defaultMountAccountPanel } from './account-panel.js';
import { fetchSettings as _defaultFetchSettings } from '../api/account-api.js';
import { updateProfile as _defaultUpdateProfile } from '../api/account-api.js';
import { updateNotifications as _defaultUpdateNotifications } from '../api/account-api.js';
import { clearSlack as _defaultClearSlack } from '../api/account-api.js';
import { sendSlackTest as _defaultSendSlackTest } from '../api/account-api.js';
import { signOut as _defaultSignOut } from '../api/auth-api.js';
import { settingsErrorMessage } from './settings-errors.js';
import { settingsModalBodyTemplate } from './settings-modal-template.js';

const STYLE_ID = 'rt-settings-modal-styles';

const TABS = [
  { id: 'profile', label: 'Profile' },
  { id: 'notifications', label: 'Notifications' },
  { id: 'account', label: 'Account' },
];

/**
 * Mounts the Settings modal into a self-managed host element.
 *
 * @param {{
 *   _mountModal?: Function,
 *   _mountTabs?: Function,
 *   _mountBanner?: Function,
 *   _mountProfilePanel?: Function,
 *   _mountNotificationsPanel?: Function,
 *   _mountAccountPanel?: Function,
 *   _fetchSettings?: Function,
 *   _updateProfile?: Function,
 *   _updateNotifications?: Function,
 *   _clearSlack?: Function,
 *   _sendSlackTest?: Function,
 *   _signOut?: Function,
 * }} [config]
 * @returns {{ dispose(): void }}
 */
export function mountSettingsModal(config = {}) {
  const {
    _mountModal = _defaultMountModal,
    _mountTabs = _defaultMountTabs,
    _mountBanner = _defaultMountBanner,
    _mountProfilePanel = _defaultMountProfilePanel,
    _mountNotificationsPanel = _defaultMountNotificationsPanel,
    _mountAccountPanel = _defaultMountAccountPanel,
    _fetchSettings = _defaultFetchSettings,
    _updateProfile = _defaultUpdateProfile,
    _updateNotifications = _defaultUpdateNotifications,
    _clearSlack = _defaultClearSlack,
    _sendSlackTest = _defaultSendSlackTest,
    _signOut = _defaultSignOut,
  } = config;

  injectStyles();

  // Create a self-managed host element and attach to document body.
  const host = makeHost();
  if (typeof document !== 'undefined' && document.body) {
    document.body.appendChild(host);
  }

  // ── State ──────────────────────────────────────────────────────────────────
  let settings = null;
  let activeTabId = TABS[0].id;
  let activePanelCtrl = null;
  let tabsCtrl = null;
  let bannerCtrl = null;
  let saveBtn = null;
  let saving = false;

  // ── Modal ──────────────────────────────────────────────────────────────────
  const modal = _mountModal(host, {
    title: 'Settings',
    sheetOnMobile: true,
    onClose: dispose,
    closeOnBackdrop: true,
    // Wider than the default 480px — this is a two-column (rail + panel) modal.
    width: '720px',
  });

  // Build the body shell and inject it into the modal.
  const bodyHost = makeHost();
  bodyHost.innerHTML = settingsModalBodyTemplate();

  // Grab element refs from body shell (null-safe for stub environments).
  const bannerHost = bodyHost.querySelector
    ? bodyHost.querySelector('[data-host="banner"]')
    : null;
  const tabsHost = bodyHost.querySelector
    ? bodyHost.querySelector('[data-host="tabs"]')
    : null;
  const panelHost = bodyHost.querySelector
    ? bodyHost.querySelector('[data-host="panel"]')
    : null;
  saveBtn = bodyHost.querySelector
    ? bodyHost.querySelector('[data-action="save"]')
    : null;

  modal.setBody(bodyHost);

  // ── Save button ────────────────────────────────────────────────────────────
  function setSaveEnabled(enabled) {
    if (saveBtn) {
      saveBtn.disabled = !enabled;
    }
  }

  function onDirtyChange(dirty) {
    setSaveEnabled(dirty && !saving);
  }

  // ── Banner ──────────────────────────────────────────────────────────────────
  function showBanner(type, message) {
    if (!bannerHost) return;
    if (bannerCtrl) {
      bannerCtrl.update({ type, message });
    } else {
      bannerCtrl = _mountBanner(bannerHost, { type, message, dismissable: true });
    }
  }

  function clearBanner() {
    if (bannerCtrl) {
      bannerCtrl.dispose();
      bannerCtrl = null;
    }
  }

  // ── Panel mounting ─────────────────────────────────────────────────────────
  function disposeActivePanel() {
    if (activePanelCtrl) {
      activePanelCtrl.dispose();
      activePanelCtrl = null;
    }
  }

  function mountPanel(tabId, loadedSettings) {
    // dispose-before-render: always dispose before mounting a new panel
    disposeActivePanel();
    setSaveEnabled(false);

    if (!panelHost) return;

    if (tabId === 'profile') {
      activePanelCtrl = _mountProfilePanel(panelHost, {
        settings: loadedSettings,
        onDirtyChange,
      });
    } else if (tabId === 'notifications') {
      activePanelCtrl = _mountNotificationsPanel(panelHost, {
        settings: loadedSettings,
        onDirtyChange,
        onTest: (channel) => _sendSlackTest(channel),
      });
    } else if (tabId === 'account') {
      activePanelCtrl = _mountAccountPanel(panelHost, {
        settings: loadedSettings,
        onSignOut: handleSignOut,
        onDisconnectSlack: handleDisconnectSlack,
      });
      // Account tab never has Save — keep button disabled
      setSaveEnabled(false);
    }
  }

  // ── Tab switching ──────────────────────────────────────────────────────────
  function onTabChange(tabId) {
    activeTabId = tabId;
    clearBanner();
    if (settings) {
      mountPanel(tabId, settings);
    }
  }

  // ── Tabs ───────────────────────────────────────────────────────────────────
  if (tabsHost) {
    tabsCtrl = _mountTabs(tabsHost, {
      tabs: TABS,
      active: activeTabId,
      onChange: onTabChange,
    });
  }

  // ── Load settings ──────────────────────────────────────────────────────────
  // Show loading placeholder while fetching.
  if (panelHost) {
    panelHost.innerHTML = '<div class="rt-settings-modal-loading">Loading…</div>';
  }

  _fetchSettings()
    .then((data) => {
      settings = data;
      mountPanel(activeTabId, settings);
    })
    .catch((err) => {
      const msg = settingsErrorMessage(err && err.code);
      showBanner('error', msg);
      if (panelHost) panelHost.innerHTML = '';
    });

  // ── Save handler ───────────────────────────────────────────────────────────
  async function _save() {
    if (!activePanelCtrl || saving) return;
    saving = true;
    setSaveEnabled(false);

    try {
      let updatedSettings;

      if (activeTabId === 'profile') {
        updatedSettings = await _updateProfile(activePanelCtrl.getPayload());
      } else if (activeTabId === 'notifications') {
        updatedSettings = await _updateNotifications(activePanelCtrl.getPayload());
      } else {
        // Account tab has no Save — should not be reachable
        return;
      }

      // Re-read settings (refresh masked hints etc.)
      try {
        settings = await _fetchSettings();
      } catch (_) {
        // If re-read fails, use the response from the save call if available
        if (updatedSettings) settings = updatedSettings;
      }

      showBanner('success', 'Settings saved.');
      mountPanel(activeTabId, settings);
    } catch (err) {
      const msg = settingsErrorMessage(err && err.code);
      showBanner('error', msg);
      // Re-enable Save if panel is still dirty
      const stillDirty = activePanelCtrl ? activePanelCtrl.isDirty() : false;
      setSaveEnabled(stillDirty);
    } finally {
      saving = false;
    }
  }

  // Wire the Save button click.
  if (saveBtn) {
    saveBtn.addEventListener('click', _save);
  }

  // ── Action handlers ────────────────────────────────────────────────────────
  function handleSignOut() {
    _signOut();
  }

  async function handleDisconnectSlack() {
    try {
      await _clearSlack();
      settings = await _fetchSettings();
      clearBanner();
      mountPanel(activeTabId, settings);
    } catch (err) {
      const msg = settingsErrorMessage(err && err.code);
      showBanner('error', msg);
    }
  }

  // ── Dispose ────────────────────────────────────────────────────────────────
  function dispose() {
    if (saveBtn) saveBtn.removeEventListener('click', _save);
    disposeActivePanel();
    if (tabsCtrl) { tabsCtrl.dispose(); tabsCtrl = null; }
    if (bannerCtrl) { bannerCtrl.dispose(); bannerCtrl = null; }
    modal.dispose();
    if (host.parentNode) host.parentNode.removeChild(host);
  }

  // Expose _save for testability (named export on the returned object).
  return { dispose, _save };
}

// ── Helpers ────────────────────────────────────────────────────────────────────

function makeHost() {
  if (typeof document !== 'undefined' && document.createElement) {
    return document.createElement('div');
  }
  // Minimal stub for test environments without a real document.
  return {
    innerHTML: '',
    _listeners: {},
    appendChild(child) { this._children = this._children || []; this._children.push(child); },
    removeChild(child) {
      if (this._children) {
        const i = this._children.indexOf(child);
        if (i !== -1) this._children.splice(i, 1);
      }
    },
    querySelector(selector) { return null; },
    addEventListener(event, fn) {
      this._listeners[event] = this._listeners[event] || [];
      this._listeners[event].push(fn);
    },
    removeEventListener(event, fn) {
      if (this._listeners[event]) {
        this._listeners[event] = this._listeners[event].filter(f => f !== fn);
      }
    },
    get parentNode() { return null; },
  };
}

function injectStyles() {
  if (typeof document === 'undefined') return;
  if (document.getElementById(STYLE_ID)) return;
  const link = document.createElement('link');
  link.id = STYLE_ID;
  link.rel = 'stylesheet';
  link.href = '/web/account/settings-modal.css';
  document.head.appendChild(link);
}
