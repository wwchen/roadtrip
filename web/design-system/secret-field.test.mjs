import assert from 'node:assert/strict';
import test from 'node:test';

import {
  initialState,
  toReplacing,
  toCancelled,
  withInput,
  valueOf,
} from './secret-field-template.js';
import { secretFieldTemplate } from './secret-field-template.js';
import { mountSecretField } from './secret-field.js';

// ── Pure reducer tests ────────────────────────────────────────────────────────

test('initialState(hint) starts in stored mode', () => {
  const s = initialState('3f9a');
  assert.equal(s.mode, 'stored');
  assert.equal(s.hint, '3f9a');
  assert.equal(s.value, '');
});

test('valueOf in stored mode returns null', () => {
  const s = initialState('3f9a');
  assert.equal(valueOf(s), null);
});

test('initialState(null) starts in replacing mode', () => {
  const s = initialState(null);
  assert.equal(s.mode, 'replacing');
  assert.equal(valueOf(s), '');
});

test('initialState(undefined) starts in replacing mode', () => {
  const s = initialState(undefined);
  assert.equal(s.mode, 'replacing');
});

test('toReplacing transitions stored → replacing', () => {
  const s = toReplacing(initialState('3f9a'));
  assert.equal(s.mode, 'replacing');
  assert.equal(s.value, '');
});

test('toCancelled transitions replacing → stored (with hint)', () => {
  const stored = initialState('3f9a');
  const replacing = toReplacing(stored);
  const cancelled = toCancelled(replacing, '3f9a');
  assert.equal(cancelled.mode, 'stored');
  assert.equal(cancelled.hint, '3f9a');
});

test('withInput updates value in replacing mode', () => {
  const s = withInput(initialState(null), 'xoxb-new');
  assert.equal(s.value, 'xoxb-new');
  assert.equal(valueOf(s), 'xoxb-new');
});

test('valueOf in replacing mode returns the entered value', () => {
  const s = withInput(toReplacing(initialState('3f9a')), 'xoxb-new');
  assert.equal(valueOf(s), 'xoxb-new');
});

test('valueOf in replacing mode with empty input returns empty string', () => {
  const s = toReplacing(initialState('3f9a'));
  assert.equal(valueOf(s), '');
});

// ── Pure template tests ───────────────────────────────────────────────────────

test('secretFieldTemplate in stored mode renders masked value and Replace button', () => {
  const s = initialState('3f9a');
  const html = secretFieldTemplate(s, { label: 'Slack bot token' });
  assert.match(html, /•{4}3f9a/);          // ••••3f9a
  assert.match(html, /data-action="replace"/);
  assert.doesNotMatch(html, /data-action="cancel"/);
  assert.doesNotMatch(html, /<input/);
});

test('secretFieldTemplate in replacing mode renders input and Cancel button', () => {
  const s = toReplacing(initialState('3f9a'));
  const html = secretFieldTemplate(s, { label: 'Slack bot token' });
  assert.match(html, /<input/);
  assert.match(html, /data-action="cancel"/);
  assert.doesNotMatch(html, /data-action="replace"/);
});

test('secretFieldTemplate in replacing mode with no hint omits Cancel button', () => {
  const s = initialState(null);
  const html = secretFieldTemplate(s, { label: 'Slack bot token' });
  assert.match(html, /<input/);
  assert.doesNotMatch(html, /data-action="cancel"/);
});

test('secretFieldTemplate escapes the label', () => {
  const s = initialState(null);
  const html = secretFieldTemplate(s, { label: '<script>alert(1)</script>' });
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});

test('secretFieldTemplate escapes the hint', () => {
  const s = initialState('<b>xss</b>');
  const html = secretFieldTemplate(s, { label: 'Token' });
  assert.match(html, /&lt;b&gt;xss&lt;\/b&gt;/);
  assert.doesNotMatch(html, /<b>xss<\/b>/);
});

test('secretFieldTemplate renders help text when provided', () => {
  const s = initialState(null);
  const html = secretFieldTemplate(s, { label: 'Token', help: 'Find this in Slack API settings' });
  assert.match(html, /Find this in Slack API settings/);
});

test('secretFieldTemplate escapes the help text', () => {
  const s = initialState(null);
  const html = secretFieldTemplate(s, { label: 'Token', help: '<script>x</script>' });
  assert.match(html, /&lt;script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});

test('secretFieldTemplate uses monospace class on masked value', () => {
  const s = initialState('3f9a');
  const html = secretFieldTemplate(s, { label: 'Token' });
  assert.match(html, /rt-secret-field-masked/);
});

// ── Stub-mount smoke tests ────────────────────────────────────────────────────

function makeStubDocument() {
  let injectedLink = null;
  return {
    getElementById() { return null; },
    createElement(tagName) {
      const el = { id: '', rel: '', href: '', tagName };
      if (tagName === 'link') injectedLink = el;
      return el;
    },
    head: {
      appendChild(el) { injectedLink = el; },
    },
    addEventListener() {},
    removeEventListener() {},
    _getInjectedLink() { return injectedLink; },
  };
}

test('stub-mount: mountSecretField(stored) sets host.innerHTML with masked value', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const ctrl = mountSecretField(host, { label: 'Slack bot token', hint: '3f9a' });

    assert.match(host.innerHTML, /•{4}3f9a/);
    assert.match(host.innerHTML, /data-action="replace"/);
    assert.equal(ctrl.getMode(), 'stored');
    assert.equal(ctrl.getValue(), null);
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('stub-mount: mountSecretField(no hint) starts in replacing mode', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const ctrl = mountSecretField(host, { label: 'Slack bot token', hint: null });

    assert.match(host.innerHTML, /<input/);
    assert.equal(ctrl.getMode(), 'replacing');
    assert.equal(ctrl.getValue(), '');
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('stub-mount: dispose clears host.innerHTML', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  let removedListener = false;
  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() { removedListener = true; },
  };

  try {
    const ctrl = mountSecretField(host, { label: 'Token', hint: '3f9a' });
    ctrl.dispose();
    assert.equal(host.innerHTML, '');
    assert.ok(removedListener, 'removeEventListener should have been called');
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('stub-mount: injectStyles injects stylesheet link', () => {
  const originalDocument = globalThis.document;
  const stubDoc = makeStubDocument();
  globalThis.document = stubDoc;

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    mountSecretField(host, { label: 'Token', hint: '3f9a' });
    const link = stubDoc._getInjectedLink();
    assert.ok(link, 'link element should have been injected');
    assert.match(link.href, /secret-field\.css/);
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('stub-mount: reset restores initial state', () => {
  const originalDocument = globalThis.document;
  globalThis.document = makeStubDocument();

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const ctrl = mountSecretField(host, { label: 'Token', hint: '3f9a' });
    assert.equal(ctrl.getMode(), 'stored');
    ctrl.reset();
    assert.equal(ctrl.getMode(), 'stored');
    assert.equal(ctrl.getValue(), null);
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});
