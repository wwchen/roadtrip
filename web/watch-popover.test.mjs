import assert from 'node:assert/strict';
import test from 'node:test';

import { mountWatchPopover } from './availability/watch-popover.js';

const originalDocument = globalThis.document;
const originalElement = globalThis.Element;

class FakeHost {
  constructor() {
    this.innerHTML = '';
    this.listeners = new Map();
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  removeEventListener(type) {
    this.listeners.delete(type);
  }

  contains() {
    return false;
  }
}

class FakeElement {
  constructor(action = null) {
    this.action = action;
  }

  closest(selector) {
    if (selector === '.cg-watch-pop-action') return this.action;
    return null;
  }
}

function installDomStubs() {
  const documentListeners = new Map();
  globalThis.Element = FakeElement;
  globalThis.document = {
    addEventListener(type, listener) {
      documentListeners.set(type, listener);
    },
    removeEventListener(type) {
      documentListeners.delete(type);
    },
  };
}

function restoreDomStubs() {
  if (originalDocument === undefined) {
    delete globalThis.document;
  } else {
    globalThis.document = originalDocument;
  }
  if (originalElement === undefined) {
    delete globalThis.Element;
  } else {
    globalThis.Element = originalElement;
  }
}

test('watch popover renders unavailable state without a set action', async () => {
  installDomStubs();
  let setCalls = 0;
  try {
    const host = new FakeHost();
    const controller = mountWatchPopover(host, {
      poiName: 'Illecillewaet',
      date: '2026-07-21',
      watching: false,
      canCreate: false,
      onSet: async () => {
        setCalls += 1;
      },
      onRemove: async () => {},
      onClose: () => {},
    });
    await new Promise((resolve) => setTimeout(resolve, 0));

    assert.match(host.innerHTML, /Watch unavailable/);
    assert.match(host.innerHTML, /Watches are not available for this campground\./);
    assert.match(host.innerHTML, /disabled/);
    assert.doesNotMatch(host.innerHTML, /Alerts post to Slack/);

    await host.listeners.get('click')({
      target: new FakeElement({ disabled: false }),
    });

    assert.equal(setCalls, 0);
    controller.dispose();
  } finally {
    restoreDomStubs();
  }
});
