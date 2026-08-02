import assert from 'node:assert/strict';
import test from 'node:test';

import { alertEditorContainsFocus, shouldHideAlerts } from './topbar/alerts.js';

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
