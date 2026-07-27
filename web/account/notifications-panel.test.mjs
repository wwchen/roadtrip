import assert from 'node:assert/strict';
import test from 'node:test';

import {
  computeNotificationsDirty,
  buildNotificationsPayload,
  mountNotificationsPanel,
} from './notifications-panel.js';

// ── Pure helper tests ─────────────────────────────────────────────────────────

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

test('computeNotificationsDirty: false when values match settings', () => {
  assert.equal(
    computeNotificationsDirty(BASE_SETTINGS, {
      notification_email: 'alice@example.com',
      slack_channel: '#alerts',
    }),
    false,
  );
});

test('computeNotificationsDirty: true when email changed', () => {
  assert.equal(
    computeNotificationsDirty(BASE_SETTINGS, {
      notification_email: 'other@example.com',
      slack_channel: '#alerts',
    }),
    true,
  );
});

test('computeNotificationsDirty: true when channel changed', () => {
  assert.equal(
    computeNotificationsDirty(BASE_SETTINGS, {
      notification_email: 'alice@example.com',
      slack_channel: '#new-channel',
    }),
    true,
  );
});

test('computeNotificationsDirty: false when both null/empty match empty settings', () => {
  const settings = {
    ...BASE_SETTINGS,
    notifications: { ...BASE_SETTINGS.notifications, notification_email: null, slack_channel: null },
  };
  assert.equal(
    computeNotificationsDirty(settings, { notification_email: '', slack_channel: '' }),
    false,
  );
});

test('buildNotificationsPayload: includes all fields when slack_token is a string', () => {
  const payload = buildNotificationsPayload({
    notification_email: 'alice@example.com',
    slack_channel: '#alerts',
    slack_token: 'xoxb-new-token',
  });
  assert.deepEqual(payload, {
    notification_email: 'alice@example.com',
    slack_channel: '#alerts',
    slack_token: 'xoxb-new-token',
  });
});

test('buildNotificationsPayload: slack_token is null when unchanged (stored mode)', () => {
  const payload = buildNotificationsPayload({
    notification_email: 'alice@example.com',
    slack_channel: '#alerts',
    slack_token: null,
  });
  assert.equal(payload.slack_token, null);
  assert.equal(payload.notification_email, 'alice@example.com');
  assert.equal(payload.slack_channel, '#alerts');
});

// ── Stub-mount smoke tests ────────────────────────────────────────────────────

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

function makeFakeFormSection(initialValue = '') {
  let value = initialValue;
  return {
    getValue() { return value; },
    update({ value: v } = {}) { if (v != null) value = v; },
    dispose() {},
  };
}

function makeFakeSecretField(hint = null) {
  const mode = hint ? 'stored' : 'replacing';
  return {
    getValue() { return mode === 'stored' ? null : ''; },
    getMode() { return mode; },
    reset() {},
    dispose() {},
  };
}

function makeFakeBanner() {
  return {
    update() {},
    dispose() {},
  };
}

test('stub-mount: mountNotificationsPanel — getPayload returns initial values', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  const fakeSecretField = makeFakeSecretField('3f9a');

  let mountCount = 0;
  const fakeMountFormSection = (_host, cfg) => {
    mountCount++;
    if (cfg.name === 'notification_email') return fakeEmailField;
    return fakeChannelField;
  };
  const fakeMountSecretField = () => fakeSecretField;
  const fakeMountBanner = () => makeFakeBanner();

  const container = makeStubHost();

  try {
    const ctrl = mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
      _mountBanner: fakeMountBanner,
    });

    const payload = ctrl.getPayload();
    assert.equal(payload.notification_email, 'alice@example.com');
    assert.equal(payload.slack_channel, '#alerts');
    // slack_token is null because secret field is in stored mode
    assert.equal(payload.slack_token, null);
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountNotificationsPanel — isDirty() starts false', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  const fakeSecretField = makeFakeSecretField('3f9a');

  const fakeMountFormSection = (_host, cfg) =>
    cfg.name === 'notification_email' ? fakeEmailField : fakeChannelField;
  const fakeMountSecretField = () => fakeSecretField;
  const fakeMountBanner = () => makeFakeBanner();

  const container = makeStubHost();

  try {
    const ctrl = mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
      _mountBanner: fakeMountBanner,
    });

    assert.equal(ctrl.isDirty(), false);
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountNotificationsPanel — dispose clears innerHTML', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  const fakeSecretField = makeFakeSecretField('3f9a');

  const fakeMountFormSection = (_host, cfg) =>
    cfg.name === 'notification_email' ? fakeEmailField : fakeChannelField;
  const fakeMountSecretField = () => fakeSecretField;
  const fakeMountBanner = () => makeFakeBanner();

  const container = makeStubHost();

  try {
    const ctrl = mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
      _mountBanner: fakeMountBanner,
    });

    ctrl.dispose();
    assert.equal(container.innerHTML, '');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountNotificationsPanel — slack_token null in stored mode', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  // Stored mode (hint present) → getValue() returns null
  const fakeSecretField = makeFakeSecretField('3f9a');

  const fakeMountFormSection = (_host, cfg) =>
    cfg.name === 'notification_email' ? fakeEmailField : fakeChannelField;
  const fakeMountSecretField = () => fakeSecretField;
  const fakeMountBanner = () => makeFakeBanner();

  const container = makeStubHost();

  try {
    const ctrl = mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
      _mountBanner: fakeMountBanner,
    });

    const payload = ctrl.getPayload();
    assert.equal(payload.slack_token, null, 'slack_token should be null in stored mode');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});
