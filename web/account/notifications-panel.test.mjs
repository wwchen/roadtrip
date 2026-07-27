import assert from 'node:assert/strict';
import test from 'node:test';

import {
  computeNotificationsDirty,
  buildNotificationsPayload,
  mountNotificationsPanel,
} from './notifications-panel.js';

import { settingsErrorMessage } from './settings-errors.js';

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

/**
 * Make a stub container whose querySelector returns observable status span stubs
 * keyed on data-host attribute.
 */
function makeStubHost() {
  // Stable status span stubs returned by querySelector for data-host selectors.
  const statusSpans = {};
  function makeStatusSpan() {
    return { textContent: '', className: 'rt-notif-status' };
  }

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
    querySelector(selector) {
      // Return stable span stubs for the inline status hosts.
      const m = selector && selector.match(/data-host="([^"]+)"/);
      if (m) {
        const key = m[1];
        if (!statusSpans[key]) statusSpans[key] = makeStatusSpan();
        return statusSpans[key];
      }
      return makeStubHost();
    },
    _statusSpans: statusSpans,
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

  const container = makeStubHost();

  try {
    const ctrl = mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
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

  const container = makeStubHost();

  try {
    const ctrl = mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
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

  const container = makeStubHost();

  try {
    const ctrl = mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
    });

    ctrl.dispose();
    assert.equal(container.innerHTML, '');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountNotificationsPanel — onTestEmail called when test-email button clicked', async () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  const fakeSecretField = makeFakeSecretField('3f9a');

  const fakeMountFormSection = (_host, cfg) =>
    cfg.name === 'notification_email' ? fakeEmailField : fakeChannelField;
  const fakeMountSecretField = () => fakeSecretField;

  const container = makeStubHost();

  const testEmailCalls = [];

  // Simulate click events on the container listeners.
  function fireClick(action) {
    const listeners = container._listeners['click'] || [];
    const fakeEvent = {
      target: {
        closest(selector) {
          if (selector === `[data-action="${action}"]`) return {};
          return null;
        },
      },
    };
    listeners.forEach(fn => fn(fakeEvent));
  }

  try {
    mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      onTestEmail: async () => { testEmailCalls.push(true); },
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
    });

    fireClick('test-email');
    // Allow async handleTestEmail to settle.
    await Promise.resolve();
    await Promise.resolve();

    assert.equal(testEmailCalls.length, 1, 'onTestEmail should be called once');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountNotificationsPanel — onTestEmail success sets inline email-status to ok', async () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  const fakeSecretField = makeFakeSecretField('3f9a');

  const fakeMountFormSection = (_host, cfg) =>
    cfg.name === 'notification_email' ? fakeEmailField : fakeChannelField;
  const fakeMountSecretField = () => fakeSecretField;

  const container = makeStubHost();

  function fireClick(action) {
    const listeners = container._listeners['click'] || [];
    const fakeEvent = {
      target: { closest(selector) { return selector === `[data-action="${action}"]` ? {} : null; } },
    };
    listeners.forEach(fn => fn(fakeEvent));
  }

  try {
    mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      onTestEmail: async () => { /* success */ },
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
    });

    fireClick('test-email');
    await Promise.resolve();
    await Promise.resolve();

    const emailStatus = container._statusSpans['email-status'];
    assert.ok(emailStatus, 'email-status span should exist');
    assert.match(emailStatus.textContent, /✓/);
    assert.match(emailStatus.className, /rt-notif-status--ok/);
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountNotificationsPanel — onTestEmail failure sets inline email-status to err', async () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  const fakeSecretField = makeFakeSecretField('3f9a');

  const fakeMountFormSection = (_host, cfg) =>
    cfg.name === 'notification_email' ? fakeEmailField : fakeChannelField;
  const fakeMountSecretField = () => fakeSecretField;

  const container = makeStubHost();

  function fireClick(action) {
    const listeners = container._listeners['click'] || [];
    const fakeEvent = {
      target: { closest(selector) { return selector === `[data-action="${action}"]` ? {} : null; } },
    };
    listeners.forEach(fn => fn(fakeEvent));
  }

  try {
    mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      onTestEmail: async () => { throw { code: 'email_send_failed' }; },
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
    });

    fireClick('test-email');
    await Promise.resolve();
    await Promise.resolve();

    const emailStatus = container._statusSpans['email-status'];
    assert.ok(emailStatus, 'email-status span should exist');
    assert.match(emailStatus.textContent, /✕/);
    assert.match(emailStatus.textContent, new RegExp(settingsErrorMessage('email_send_failed')));
    assert.match(emailStatus.className, /rt-notif-status--err/);
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountNotificationsPanel — onTest (slack) success sets inline slack-status to ok', async () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  const fakeSecretField = makeFakeSecretField('3f9a');

  const fakeMountFormSection = (_host, cfg) =>
    cfg.name === 'notification_email' ? fakeEmailField : fakeChannelField;
  const fakeMountSecretField = () => fakeSecretField;

  const container = makeStubHost();

  function fireClick(action) {
    const listeners = container._listeners['click'] || [];
    const fakeEvent = {
      target: { closest(selector) { return selector === `[data-action="${action}"]` ? {} : null; } },
    };
    listeners.forEach(fn => fn(fakeEvent));
  }

  try {
    mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      onTest: async () => { /* success */ },
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
    });

    fireClick('test-slack');
    await Promise.resolve();
    await Promise.resolve();

    const slackStatus = container._statusSpans['slack-status'];
    assert.ok(slackStatus, 'slack-status span should exist');
    assert.match(slackStatus.textContent, /✓/);
    assert.match(slackStatus.className, /rt-notif-status--ok/);
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountNotificationsPanel — onTest (slack) failure sets inline slack-status to err', async () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeEmailField = makeFakeFormSection('alice@example.com');
  const fakeChannelField = makeFakeFormSection('#alerts');
  const fakeSecretField = makeFakeSecretField('3f9a');

  const fakeMountFormSection = (_host, cfg) =>
    cfg.name === 'notification_email' ? fakeEmailField : fakeChannelField;
  const fakeMountSecretField = () => fakeSecretField;

  const container = makeStubHost();

  function fireClick(action) {
    const listeners = container._listeners['click'] || [];
    const fakeEvent = {
      target: { closest(selector) { return selector === `[data-action="${action}"]` ? {} : null; } },
    };
    listeners.forEach(fn => fn(fakeEvent));
  }

  try {
    mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      onTest: async () => { throw { code: 'slack_send_failed' }; },
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
    });

    fireClick('test-slack');
    await Promise.resolve();
    await Promise.resolve();

    const slackStatus = container._statusSpans['slack-status'];
    assert.ok(slackStatus, 'slack-status span should exist');
    assert.match(slackStatus.textContent, /✕/);
    assert.match(slackStatus.textContent, new RegExp(settingsErrorMessage('slack_send_failed')));
    assert.match(slackStatus.className, /rt-notif-status--err/);
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

  const container = makeStubHost();

  try {
    const ctrl = mountNotificationsPanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMountFormSection,
      _mountSecretField: fakeMountSecretField,
    });

    const payload = ctrl.getPayload();
    assert.equal(payload.slack_token, null, 'slack_token should be null in stored mode');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});
