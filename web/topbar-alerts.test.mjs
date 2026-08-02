import assert from 'node:assert/strict';
import test from 'node:test';

import { alertEditorContainsFocus, shouldHideAlerts, hasAlertDeepLink, renderSignInPrompt } from './topbar/alerts.js';

test('alertEditorContainsFocus detects active controls inside the alert editor host', () => {
  const originalElement = globalThis.Element;

  class FakeElement {}
  globalThis.Element = FakeElement;

  const editorHost = new FakeElement();
  const editorInput = new FakeElement();
  const rowButton = new FakeElement();
  const root = {
    contains(element) {
      return element === editorInput || element === rowButton;
    },
  };

  editorInput.closest = (selector) => (selector === '.tb-alerts-editor-host' ? editorHost : null);
  rowButton.closest = () => null;

  try {
    assert.equal(alertEditorContainsFocus(root, editorInput), true);
    assert.equal(alertEditorContainsFocus(root, rowButton), false);
    assert.equal(alertEditorContainsFocus(root, null), false);
  } finally {
    if (originalElement === undefined) {
      delete globalThis.Element;
    } else {
      globalThis.Element = originalElement;
    }
  }
});

test('shouldHideAlerts is true for a 401 and false otherwise', () => {
  assert.equal(shouldHideAlerts({ status: 401 }), true);
  assert.equal(shouldHideAlerts({ status: 500 }), false);
  assert.equal(shouldHideAlerts(null), false);
  assert.equal(shouldHideAlerts(new Error('network')), false);
});

test('hasAlertDeepLink detects presence of alert param', () => {
  const originalWindow = globalThis.window;

  try {
    // Mock window.location with alert param
    globalThis.window = { location: { search: '?alert=123&alert_action=pause' } };
    assert.equal(hasAlertDeepLink(), true);

    // Mock window.location without alert param
    globalThis.window = { location: { search: '?foo=bar' } };
    assert.equal(hasAlertDeepLink(), false);

    // Mock window.location with empty search
    globalThis.window = { location: { search: '' } };
    assert.equal(hasAlertDeepLink(), false);

    // Mock window.location with empty alert param
    globalThis.window = { location: { search: '?alert=' } };
    assert.equal(hasAlertDeepLink(), false);
  } finally {
    if (originalWindow === undefined) {
      delete globalThis.window;
    } else {
      globalThis.window = originalWindow;
    }
  }
});

test('renderSignInPrompt adds visible class and sets content', () => {
  const classList = {
    items: new Set(),
    add(name) {
      this.items.add(name);
    },
    remove(name) {
      this.items.delete(name);
    },
    contains(name) {
      return this.items.has(name);
    },
  };

  const fakeRoot = {
    hidden: true,
    innerHTML: '',
    classList,
  };

  renderSignInPrompt(fakeRoot);

  assert.equal(fakeRoot.hidden, false, 'rootEl.hidden should be false');
  assert.equal(classList.contains('visible'), true, 'rootEl should have visible class');
  assert.ok(fakeRoot.innerHTML.includes('Sign in to view this alert'), 'innerHTML should contain sign-in message');
});
