import assert from 'node:assert/strict';
import test from 'node:test';

import {
  computeProfileDirty,
  buildProfilePayload,
  mountProfilePanel,
} from './profile-panel.js';

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
    notification_email: '',
    slack_channel: '',
    slack_configured: false,
    slack_token_hint: null,
  },
};

test('computeProfileDirty: false when display_name matches settings', () => {
  assert.equal(
    computeProfileDirty(BASE_SETTINGS, { display_name: 'Alice' }),
    false,
  );
});

test('computeProfileDirty: true when display_name changed', () => {
  assert.equal(
    computeProfileDirty(BASE_SETTINGS, { display_name: 'Bob' }),
    true,
  );
});

test('computeProfileDirty: false when both are empty string', () => {
  const settings = { profile: { ...BASE_SETTINGS.profile, display_name: '' } };
  assert.equal(
    computeProfileDirty(settings, { display_name: '' }),
    false,
  );
});

test('computeProfileDirty: false when settings.display_name is null and value is empty', () => {
  const settings = { profile: { ...BASE_SETTINGS.profile, display_name: null } };
  assert.equal(
    computeProfileDirty(settings, { display_name: '' }),
    false,
  );
});

test('buildProfilePayload: returns display_name', () => {
  const payload = buildProfilePayload({ display_name: 'Alice' });
  assert.deepEqual(payload, { display_name: 'Alice' });
});

test('buildProfilePayload: returns empty display_name', () => {
  const payload = buildProfilePayload({ display_name: '' });
  assert.deepEqual(payload, { display_name: '' });
});

// ── Stub-mount smoke tests ────────────────────────────────────────────────────

function makeStubDocument() {
  return {
    getElementById() { return null; },
    createElement(tagName) {
      const el = { id: '', rel: '', href: '', tagName };
      return el;
    },
    head: { appendChild() {} },
  };
}

function makeStubHost(innerHTML = '') {
  const host = {
    innerHTML,
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
      return makeStubHost();
    },
  };
  return host;
}

function makeFakeFormSection(initialValue = '') {
  let value = initialValue;
  return {
    getValue() { return value; },
    update({ value: v }) { if (v != null) value = v; },
    dispose() {},
    _setValue(v) { value = v; },
  };
}

test('stub-mount: mountProfilePanel — getPayload returns initial display_name', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const dirtyChanges = [];
  const fakeDisplayNameField = makeFakeFormSection('Alice');
  const fakeMount = (_host, _cfg) => fakeDisplayNameField;

  const container = makeStubHost();

  try {
    const ctrl = mountProfilePanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange(d) { dirtyChanges.push(d); },
      _mountFormSection: fakeMount,
    });

    const payload = ctrl.getPayload();
    assert.deepEqual(payload, { display_name: 'Alice' });
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountProfilePanel — isDirty() starts false', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeDisplayNameField = makeFakeFormSection('Alice');
  const fakeMount = (_host, _cfg) => fakeDisplayNameField;
  const container = makeStubHost();

  try {
    const ctrl = mountProfilePanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMount,
    });

    assert.equal(ctrl.isDirty(), false);
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});

test('stub-mount: mountProfilePanel — dispose clears innerHTML', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const fakeDisplayNameField = makeFakeFormSection('Alice');
  const fakeMount = (_host, _cfg) => fakeDisplayNameField;
  const container = makeStubHost();

  try {
    const ctrl = mountProfilePanel(container, {
      settings: BASE_SETTINGS,
      onDirtyChange() {},
      _mountFormSection: fakeMount,
    });

    ctrl.dispose();
    assert.equal(container.innerHTML, '');
  } finally {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
  }
});
