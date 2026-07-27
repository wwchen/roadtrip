import assert from 'node:assert/strict';
import test from 'node:test';

import { mountSettingsModal } from './settings-modal.js';

// ── Fixtures ───────────────────────────────────────────────────────────────────

const BASE_SETTINGS = {
  profile: {
    display_name: 'Alice',
    login_email: 'alice@example.com',
    is_email_verified: true,
    roles: ['user'],
    provider_label: 'Google',
  },
  notifications: {
    notification_email: 'alice@example.com',
    slack_channel: '#alerts',
    slack_configured: true,
    slack_token_hint: '3f9a',
  },
};

// ── Stub helpers ───────────────────────────────────────────────────────────────

function makeStubDocument() {
  const elements = {};
  return {
    getElementById(id) { return elements[id] || null; },
    createElement(tagName) {
      return makeStubElement(tagName);
    },
    head: { appendChild() {} },
    body: {
      _children: [],
      appendChild(child) { this._children.push(child); },
    },
  };
}

function makeStubElement(tagName = 'div') {
  // Cache querySelector results so repeated calls with the same selector return
  // the SAME element object the modal holds — this lets tests read its .disabled.
  const _queryCache = new Map();

  const el = {
    tagName,
    innerHTML: '',
    disabled: false,
    _listeners: {},
    _children: [],
    _parent: null,
    addEventListener(event, fn) {
      this._listeners[event] = this._listeners[event] || [];
      this._listeners[event].push(fn);
    },
    removeEventListener(event, fn) {
      if (this._listeners[event]) {
        this._listeners[event] = this._listeners[event].filter(f => f !== fn);
      }
    },
    appendChild(child) {
      this._children.push(child);
      child._parent = this;
    },
    removeChild(child) {
      const i = this._children.indexOf(child);
      if (i !== -1) { this._children.splice(i, 1); child._parent = null; }
    },
    querySelector(selector) {
      if (_queryCache.has(selector)) return _queryCache.get(selector);
      // Create a stable stub element keyed by selector
      let stub;
      if (selector.includes('data-action="save"')) {
        stub = makeStubElement('button');
        stub.disabled = true;
      } else {
        stub = makeStubElement('div');
      }
      _queryCache.set(selector, stub);
      return stub;
    },
    get parentNode() { return this._parent; },
  };
  return el;
}

// Fake modal — captures setBody, records dispose calls.
function makeFakeModal(capturedCalls) {
  return (container, cfg) => {
    capturedCalls.modalConfig = cfg;
    const ctrl = {
      setBody(el) { capturedCalls.bodyEl = el; },
      close() { cfg.onClose && cfg.onClose(); },
      dispose() { capturedCalls.modalDisposed = true; },
    };
    capturedCalls.modalCtrl = ctrl;
    return ctrl;
  };
}

// Fake tabs — captures onChange so tests can trigger tab switches directly.
function makeFakeTabs(capturedCalls) {
  return (container, cfg) => {
    capturedCalls.tabsConfig = cfg;
    const ctrl = {
      getActive() { return cfg.active || 'profile'; },
      setActive(id) { cfg.onChange && cfg.onChange(id); },
      dispose() { capturedCalls.tabsDisposed = true; },
    };
    capturedCalls.tabsCtrl = ctrl;
    return ctrl;
  };
}

// Fake banner — records calls.
function makeFakeBanner(capturedCalls) {
  return (container, cfg) => {
    capturedCalls.bannerType = cfg.type;
    capturedCalls.bannerMessage = cfg.message;
    capturedCalls.bannerCount = (capturedCalls.bannerCount || 0) + 1;
    const ctrl = {
      update({ type, message }) {
        capturedCalls.bannerType = type;
        capturedCalls.bannerMessage = message;
        capturedCalls.bannerUpdateCount = (capturedCalls.bannerUpdateCount || 0) + 1;
      },
      dispose() { capturedCalls.bannerDisposed = true; },
    };
    capturedCalls.bannerCtrl = ctrl;
    return ctrl;
  };
}

// Fake panel factory — records mount calls, captures onDirtyChange.
function makeFakePanel(capturedPanelCalls, panelId) {
  const record = {
    mounted: 0,
    disposeCalls: 0,
    onDirtyChange: null,
    lastSettings: null,
    isDirtyValue: false,
    payloadValue: { display_name: 'Alice' },
  };
  capturedPanelCalls[panelId] = record;

  return (container, cfg) => {
    record.mounted++;
    record.onDirtyChange = cfg.onDirtyChange;
    record.lastSettings = cfg.settings;
    if (panelId === 'notifications') {
      record.onTest = cfg.onTest;
      record.onTestEmail = cfg.onTestEmail;
    }
    if (panelId === 'account') {
      record.onSignOut = cfg.onSignOut;
      record.onDisconnectSlack = cfg.onDisconnectSlack;
    }
    const ctrl = {
      getPayload() { return record.payloadValue; },
      isDirty() { return record.isDirtyValue; },
      dispose() { record.disposeCalls++; },
    };
    record.lastCtrl = ctrl;
    return ctrl;
  };
}

// ── Test harness builder ───────────────────────────────────────────────────────

/**
 * Build a fully-wired fake environment and call mountSettingsModal.
 * `fetchSettingsImpl` controls what fetchSettings resolves/rejects with.
 */
function buildHarness({
  fetchSettingsImpl = () => Promise.resolve(BASE_SETTINGS),
  updateProfileImpl = () => Promise.resolve(BASE_SETTINGS),
  updateNotificationsImpl = () => Promise.resolve(BASE_SETTINGS),
  clearSlackImpl = () => Promise.resolve(null),
  sendSlackTestImpl = () => Promise.resolve({}),
  sendEmailTestImpl = () => Promise.resolve({}),
  signOutImpl = () => {},
} = {}) {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const calls = {};
  const panels = {};

  const ctrl = mountSettingsModal({
    _mountModal: makeFakeModal(calls),
    _mountTabs: makeFakeTabs(calls),
    _mountBanner: makeFakeBanner(calls),
    _mountProfilePanel: makeFakePanel(panels, 'profile'),
    _mountNotificationsPanel: makeFakePanel(panels, 'notifications'),
    _mountAccountPanel: makeFakePanel(panels, 'account'),
    _fetchSettings: fetchSettingsImpl,
    _updateProfile: updateProfileImpl,
    _updateNotifications: updateNotificationsImpl,
    _clearSlack: clearSlackImpl,
    _sendSlackTest: sendSlackTestImpl,
    _sendEmailTest: sendEmailTestImpl,
    _signOut: signOutImpl,
  });

  function restore() {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }

  // Convenience: return the stable save button the modal holds, via the same
  // querySelector path the modal used.  calls.bodyEl is set by makeFakeModal
  // when modal.setBody(bodyHost) is called.
  function getSaveBtn() {
    return calls.bodyEl && calls.bodyEl.querySelector('[data-action="save"]');
  }

  return { ctrl, calls, panels, restore, getSaveBtn };
}

// ── Tests ──────────────────────────────────────────────────────────────────────

test('SettingsModal: mounts a modal with title "Settings" and sheetOnMobile:true', async () => {
  const { calls, restore } = buildHarness();
  try {
    assert.equal(calls.modalConfig.title, 'Settings');
    assert.equal(calls.modalConfig.sheetOnMobile, true);
  } finally {
    restore();
  }
});

test('SettingsModal: mounts tabs with Profile/Notifications/Account', async () => {
  const { calls, restore } = buildHarness();
  try {
    const tabIds = calls.tabsConfig.tabs.map(t => t.id);
    assert.deepEqual(tabIds, ['profile', 'notifications', 'account']);
  } finally {
    restore();
  }
});

test('SettingsModal: fetchSettings is called on open and profile panel receives settings', async () => {
  const { panels, restore } = buildHarness();
  try {
    // Give the async fetchSettings a tick to resolve.
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(panels.profile.mounted, 1, 'Profile panel should be mounted once');
    assert.deepEqual(panels.profile.lastSettings, BASE_SETTINGS);
  } finally {
    restore();
  }
});

test('SettingsModal: Save is disabled on initial load (before panel is dirty)', async () => {
  const { panels, restore, getSaveBtn } = buildHarness();
  try {
    await Promise.resolve();
    await Promise.resolve();
    // Panel mounted — onDirtyChange not yet called → dirty is false.
    assert.ok(panels.profile.onDirtyChange, 'onDirtyChange should be captured');
    // The real Save button held by the modal must be disabled on initial mount.
    const saveBtn = getSaveBtn();
    assert.ok(saveBtn, 'Save button should exist on bodyEl');
    assert.equal(saveBtn.disabled, true, 'Save button should be disabled before any dirty change');
    // Explicitly notify dirty=false (no-op from clean state) → still disabled.
    panels.profile.onDirtyChange(false);
    assert.equal(saveBtn.disabled, true, 'Save button should remain disabled when dirty=false');
    // isDirty() is false by default in our fake.
    assert.equal(panels.profile.isDirtyValue, false);
  } finally {
    restore();
  }
});

test('SettingsModal: calling onDirtyChange(true) enables Save (via _save precondition)', async () => {
  const updateCalls = [];
  const { ctrl, panels, restore, getSaveBtn } = buildHarness({
    updateProfileImpl: (payload) => {
      updateCalls.push(payload);
      return Promise.resolve(BASE_SETTINGS);
    },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    const saveBtn = getSaveBtn();
    assert.ok(saveBtn, 'Save button should exist');
    assert.equal(saveBtn.disabled, true, 'Save button starts disabled');

    // Simulate the profile panel becoming dirty.
    panels.profile.isDirtyValue = true;
    panels.profile.onDirtyChange(true);

    // The Save button must now be ENABLED — this is the core save-gating behavior.
    assert.equal(saveBtn.disabled, false, 'Save button should be enabled after onDirtyChange(true)');

    // Set up payload
    panels.profile.payloadValue = { display_name: 'Bob' };

    // Call _save directly (bypasses DOM button click).
    await ctrl._save();

    assert.equal(updateCalls.length, 1, 'updateProfile should be called once');
    assert.deepEqual(updateCalls[0], { display_name: 'Bob' });
  } finally {
    restore();
  }
});

test('SettingsModal: _save on Profile tab calls updateProfile with panel payload', async () => {
  const updateCalls = [];
  const { ctrl, panels, restore } = buildHarness({
    updateProfileImpl: (payload) => {
      updateCalls.push(payload);
      return Promise.resolve(BASE_SETTINGS);
    },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    panels.profile.payloadValue = { display_name: 'Charlie' };
    panels.profile.isDirtyValue = true;
    panels.profile.onDirtyChange(true);

    await ctrl._save();

    assert.equal(updateCalls.length, 1);
    assert.deepEqual(updateCalls[0], { display_name: 'Charlie' });
  } finally {
    restore();
  }
});

test('SettingsModal: _save on Notifications tab calls updateNotifications with panel payload', async () => {
  const updateNotifCalls = [];
  const { ctrl, calls, panels, restore } = buildHarness({
    updateNotificationsImpl: (payload) => {
      updateNotifCalls.push(payload);
      return Promise.resolve(BASE_SETTINGS);
    },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    // Switch to notifications tab.
    calls.tabsConfig.onChange('notifications');

    await Promise.resolve();
    await Promise.resolve();

    const notifPayload = {
      notification_email: 'bob@example.com',
      slack_channel: '#new',
      slack_token: null,
    };
    panels.notifications.payloadValue = notifPayload;
    panels.notifications.isDirtyValue = true;
    panels.notifications.onDirtyChange(true);

    await ctrl._save();

    assert.equal(updateNotifCalls.length, 1);
    assert.deepEqual(updateNotifCalls[0], notifPayload);
  } finally {
    restore();
  }
});

test('SettingsModal: rejected save shows error Banner with settingsErrorMessage code', async () => {
  const { ctrl, calls, panels, restore } = buildHarness({
    updateNotificationsImpl: () =>
      Promise.reject({ code: 'slack_invalid_auth' }),
    fetchSettingsImpl: (() => {
      let callCount = 0;
      return () => {
        callCount++;
        // First call: load; subsequent (re-read after save fail) would not happen here
        return Promise.resolve(BASE_SETTINGS);
      };
    })(),
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    // Switch to notifications tab.
    calls.tabsConfig.onChange('notifications');

    panels.notifications.payloadValue = {
      notification_email: 'x@y.com',
      slack_channel: '#c',
      slack_token: null,
    };
    panels.notifications.isDirtyValue = true;
    panels.notifications.onDirtyChange(true);

    await ctrl._save();

    // Error banner should be shown.
    assert.ok(calls.bannerType === 'error' || calls.bannerMessage, 'An error banner should be shown');
    assert.equal(calls.bannerMessage, 'Slack rejected this token.');
  } finally {
    restore();
  }
});

test('SettingsModal: switching tabs disposes the previous panel', async () => {
  const { calls, panels, restore } = buildHarness();
  try {
    await Promise.resolve();
    await Promise.resolve();

    // Profile panel is mounted first.
    assert.equal(panels.profile.mounted, 1);

    // Switch to notifications tab → profile panel should be disposed.
    calls.tabsConfig.onChange('notifications');

    await Promise.resolve();
    await Promise.resolve();

    assert.equal(panels.profile.disposeCalls, 1, 'Profile panel should be disposed on tab switch');
    assert.equal(panels.notifications.mounted, 1, 'Notifications panel should be mounted');
  } finally {
    restore();
  }
});

test('SettingsModal: switching tabs re-scopes Save dirty state (notifications panel starts clean)', async () => {
  const { calls, panels, restore, getSaveBtn } = buildHarness();
  try {
    await Promise.resolve();
    await Promise.resolve();

    const saveBtn = getSaveBtn();
    assert.ok(saveBtn, 'Save button should exist');

    // Make profile dirty → Save enabled.
    panels.profile.isDirtyValue = true;
    panels.profile.onDirtyChange(true);
    assert.equal(saveBtn.disabled, false, 'Save should be enabled when profile is dirty');

    // Switch tab — new panel starts clean → Save must be re-disabled.
    calls.tabsConfig.onChange('notifications');

    await Promise.resolve();
    await Promise.resolve();

    // The notifications panel is fresh and isDirtyValue = false.
    assert.equal(panels.notifications.isDirtyValue, false);
    // Save must be disabled again for the freshly-mounted clean panel.
    assert.equal(saveBtn.disabled, true, 'Save button should be re-disabled after switching to a clean tab');
  } finally {
    restore();
  }
});

test('SettingsModal: Account tab onSignOut → signOut called', async () => {
  const signOutCalls = [];
  const { calls, panels, restore } = buildHarness({
    signOutImpl: () => { signOutCalls.push(true); },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    // Switch to account tab.
    calls.tabsConfig.onChange('account');

    await Promise.resolve();
    await Promise.resolve();

    assert.ok(panels.account.onSignOut, 'onSignOut should be captured');
    panels.account.onSignOut();

    assert.equal(signOutCalls.length, 1);
  } finally {
    restore();
  }
});

test('SettingsModal: Account tab onDisconnectSlack → clearSlack called then re-reads settings', async () => {
  const clearCalls = [];
  let fetchCount = 0;
  const { calls, panels, restore } = buildHarness({
    clearSlackImpl: () => { clearCalls.push(true); return Promise.resolve(null); },
    fetchSettingsImpl: () => { fetchCount++; return Promise.resolve(BASE_SETTINGS); },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    const initialFetchCount = fetchCount;

    // Switch to account tab.
    calls.tabsConfig.onChange('account');

    await Promise.resolve();
    await Promise.resolve();

    assert.ok(panels.account.onDisconnectSlack, 'onDisconnectSlack should be captured');
    await panels.account.onDisconnectSlack();

    assert.equal(clearCalls.length, 1, 'clearSlack should be called');
    assert.ok(fetchCount > initialFetchCount, 'fetchSettings should be called again after disconnect');
  } finally {
    restore();
  }
});

test('SettingsModal: successful save shows success banner and re-reads settings', async () => {
  let fetchCount = 0;
  const UPDATED_SETTINGS = { ...BASE_SETTINGS, profile: { ...BASE_SETTINGS.profile, display_name: 'Alice Updated' } };
  const { ctrl, calls, panels, restore } = buildHarness({
    updateProfileImpl: () => Promise.resolve(UPDATED_SETTINGS),
    fetchSettingsImpl: () => {
      fetchCount++;
      return Promise.resolve(UPDATED_SETTINGS);
    },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    const fetchAfterLoad = fetchCount;
    const mountAfterLoad = panels.profile.mounted;
    const disposeAfterLoad = panels.profile.disposeCalls;

    panels.profile.payloadValue = { display_name: 'Alice Updated' };
    panels.profile.isDirtyValue = true;
    panels.profile.onDirtyChange(true);

    await ctrl._save();

    // fetchSettings must be called again to re-read fresh settings.
    assert.ok(fetchCount > fetchAfterLoad, 'fetchSettings should be called again after save');
    // The success banner should be shown.
    assert.equal(calls.bannerType, 'success');
    // The active panel must be disposed before the re-mount.
    assert.ok(panels.profile.disposeCalls > disposeAfterLoad, 'Active panel should be disposed before re-mount');
    // A new panel mount must happen with the fresh settings.
    assert.ok(panels.profile.mounted > mountAfterLoad, 'Profile panel should be re-mounted after save');
    assert.deepEqual(panels.profile.lastSettings, UPDATED_SETTINGS, 'Re-mounted panel should receive fresh settings');
  } finally {
    restore();
  }
});

test('SettingsModal: dispose disposes tabs, active panel, and modal', async () => {
  const { ctrl, calls, panels, restore } = buildHarness();
  try {
    await Promise.resolve();
    await Promise.resolve();

    ctrl.dispose();

    assert.equal(calls.tabsDisposed, true, 'Tabs should be disposed');
    assert.equal(calls.modalDisposed, true, 'Modal should be disposed');
    assert.equal(panels.profile.disposeCalls, 1, 'Active panel should be disposed');
  } finally {
    restore();
  }
});

test('SettingsModal: fetchSettings failure shows error banner', async () => {
  const { calls, restore } = buildHarness({
    fetchSettingsImpl: () => Promise.reject({ code: 'unknown_code' }),
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    assert.equal(calls.bannerType, 'error');
    assert.ok(calls.bannerMessage, 'Error banner message should be set');
  } finally {
    restore();
  }
});

test('SettingsModal: _save is no-op on Account tab (no Save action)', async () => {
  const updateProfileCalls = [];
  const updateNotifCalls = [];
  const { ctrl, calls, panels, restore } = buildHarness({
    updateProfileImpl: (p) => { updateProfileCalls.push(p); return Promise.resolve(BASE_SETTINGS); },
    updateNotificationsImpl: (p) => { updateNotifCalls.push(p); return Promise.resolve(BASE_SETTINGS); },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    // Switch to account tab.
    calls.tabsConfig.onChange('account');
    await Promise.resolve();
    await Promise.resolve();

    await ctrl._save();

    assert.equal(updateProfileCalls.length, 0, 'updateProfile should not be called');
    assert.equal(updateNotifCalls.length, 0, 'updateNotifications should not be called');
  } finally {
    restore();
  }
});

test('SettingsModal: notifications panel receives onTest wired to sendSlackTest', async () => {
  const testCalls = [];
  const { calls, panels, restore } = buildHarness({
    sendSlackTestImpl: (channel) => {
      testCalls.push(channel);
      return Promise.resolve({});
    },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    // Switch to notifications tab.
    calls.tabsConfig.onChange('notifications');
    await Promise.resolve();
    await Promise.resolve();

    assert.ok(panels.notifications.onTest, 'onTest should be captured');
    await panels.notifications.onTest('#general');

    assert.equal(testCalls.length, 1);
    assert.equal(testCalls[0], '#general');
  } finally {
    restore();
  }
});

test('SettingsModal: notifications panel receives onTestEmail wired to sendEmailTest', async () => {
  const emailTestCalls = [];
  const { calls, panels, restore } = buildHarness({
    sendEmailTestImpl: () => {
      emailTestCalls.push(true);
      return Promise.resolve({});
    },
  });
  try {
    await Promise.resolve();
    await Promise.resolve();

    // Switch to notifications tab.
    calls.tabsConfig.onChange('notifications');
    await Promise.resolve();
    await Promise.resolve();

    assert.ok(panels.notifications.onTestEmail, 'onTestEmail should be captured');
    await panels.notifications.onTestEmail();

    assert.equal(emailTestCalls.length, 1);
  } finally {
    restore();
  }
});
