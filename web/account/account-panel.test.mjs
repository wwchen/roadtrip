import assert from 'node:assert/strict';
import test from 'node:test';

import { mountAccountPanel } from './account-panel.js';

// ── Fixtures ──────────────────────────────────────────────────────────────────

const BASE_SETTINGS = {
  profile: {
    display_name: 'Alice',
    login_email: 'alice@example.com',
    is_email_verified: true,
    roles: ['user'],
    provider_label: 'Google',
  },
  notifications: {
    notification_email: '',
    slack_channel: '',
    slack_configured: true,
    slack_token_hint: '3f9a',
  },
};

// ── Stub helpers ──────────────────────────────────────────────────────────────

function makeStubDocument() {
  return {
    getElementById() { return null; },
    createElement(tagName) { return { id: '', rel: '', href: '', tagName }; },
    head: { appendChild() {} },
  };
}

function makeStubHost() {
  const host = {
    innerHTML: '',
    _listeners: {},
    addEventListener(event, fn) {
      this._listeners[event] = this._listeners[event] || [];
      this._listeners[event].push(fn);
    },
    removeEventListener(event, fn) {
      if (this._listeners[event]) {
        this._listeners[event] = this._listeners[event].filter(f => f !== fn);
      }
    },
    querySelector() { return makeStubHost(); },
  };
  return host;
}

function makeFakeDoubleConfirmButton(capturedConfigs) {
  return (_host, cfg) => {
    capturedConfigs.push(cfg);
    return {
      disarm() {},
      dispose() {},
    };
  };
}

// ── AccountPanel has no editable fields ───────────────────────────────────────

test('AccountPanel: getPayload() returns empty object', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const capturedConfigs = [];
  const container = makeStubHost();

  try {
    const ctrl = mountAccountPanel(container, {
      settings: BASE_SETTINGS,
      onSignOut() {},
      onDisconnectSlack() {},
      _mountDoubleConfirmButton: makeFakeDoubleConfirmButton(capturedConfigs),
    });

    assert.deepEqual(ctrl.getPayload(), {});
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('AccountPanel: isDirty() is always false', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const capturedConfigs = [];
  const container = makeStubHost();

  try {
    const ctrl = mountAccountPanel(container, {
      settings: BASE_SETTINGS,
      onSignOut() {},
      onDisconnectSlack() {},
      _mountDoubleConfirmButton: makeFakeDoubleConfirmButton(capturedConfigs),
    });

    assert.equal(ctrl.isDirty(), false);
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('AccountPanel: passes onSignOut as onConfirm to sign-out button', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const signOutCalls = [];
  const capturedConfigs = [];
  const container = makeStubHost();

  try {
    mountAccountPanel(container, {
      settings: BASE_SETTINGS,
      onSignOut() { signOutCalls.push(true); },
      onDisconnectSlack() {},
      _mountDoubleConfirmButton: makeFakeDoubleConfirmButton(capturedConfigs),
    });

    // First button mounted should be sign-out
    assert.ok(capturedConfigs.length >= 1, 'At least one button should be mounted');
    const signOutConfig = capturedConfigs.find(c => c.label === 'Sign out');
    assert.ok(signOutConfig, 'Sign out button config should be captured');
    assert.equal(typeof signOutConfig.onConfirm, 'function');
    // Invoke the captured onConfirm to verify it calls onSignOut
    signOutConfig.onConfirm();
    assert.equal(signOutCalls.length, 1, 'onSignOut should be called via onConfirm');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('AccountPanel: passes onDisconnectSlack as onConfirm to disconnect button', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const disconnectCalls = [];
  const capturedConfigs = [];
  const container = makeStubHost();

  try {
    mountAccountPanel(container, {
      settings: BASE_SETTINGS,
      onSignOut() {},
      onDisconnectSlack() { disconnectCalls.push(true); },
      _mountDoubleConfirmButton: makeFakeDoubleConfirmButton(capturedConfigs),
    });

    const disconnectConfig = capturedConfigs.find(c => c.label === 'Disconnect Slack');
    assert.ok(disconnectConfig, 'Disconnect Slack button config should be captured');
    assert.equal(typeof disconnectConfig.onConfirm, 'function');
    disconnectConfig.onConfirm();
    assert.equal(disconnectCalls.length, 1, 'onDisconnectSlack should be called via onConfirm');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('AccountPanel: dispose clears innerHTML', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const capturedConfigs = [];
  const container = makeStubHost();

  try {
    const ctrl = mountAccountPanel(container, {
      settings: BASE_SETTINGS,
      onSignOut() {},
      onDisconnectSlack() {},
      _mountDoubleConfirmButton: makeFakeDoubleConfirmButton(capturedConfigs),
    });

    ctrl.dispose();
    assert.equal(container.innerHTML, '');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('AccountPanel: Disconnect Slack button not shown when slack not configured', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const capturedConfigs = [];
  const container = makeStubHost();

  const settingsNoSlack = {
    ...BASE_SETTINGS,
    notifications: { ...BASE_SETTINGS.notifications, slack_configured: false },
  };

  try {
    mountAccountPanel(container, {
      settings: settingsNoSlack,
      onSignOut() {},
      onDisconnectSlack() {},
      _mountDoubleConfirmButton: makeFakeDoubleConfirmButton(capturedConfigs),
    });

    const disconnectConfig = capturedConfigs.find(c => c.label === 'Disconnect Slack');
    assert.equal(disconnectConfig, undefined, 'Disconnect Slack should not be mounted when not configured');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});
