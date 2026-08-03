import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildTriggerPayload,
  mountWatchEditor,
  normalizeWatchCapabilities,
  watchEmailTo,
  watchSlackChannel,
} from './availability/watch-editor.js';
import { mountWatchPopover } from './availability/watch-popover.js';

test('buildTriggerPayload emits per-trigger Slack config', () => {
  assert.deepEqual(
    buildTriggerPayload({
      slackNotify: true,
      addToCart: true,
      stopWhenTriggered: false,
      slackChannel: ' #camping ',
    }),
    {
      trigger_kinds: ['slack_notify', 'atc'],
      trigger_config: {
        slack_notify: {
          channel: '#camping',
        },
      },
      stop_when_triggered: false,
    },
  );
});

test('buildTriggerPayload emits email notification trigger', () => {
  assert.deepEqual(
    buildTriggerPayload({
      slackNotify: false,
      emailNotify: true,
      emailTo: ' alerts@example.test ',
      addToCart: false,
      stopWhenTriggered: true,
    }),
    {
      trigger_kinds: ['email_notify'],
      trigger_config: {
        email_notify: {
          to: 'alerts@example.test',
        },
      },
      stop_when_triggered: true,
    },
  );
});

test('watchSlackChannel reads nested config before legacy flat config', () => {
  assert.equal(
    watchSlackChannel({
      trigger_config: {
        channel: '#legacy',
        slack_notify: {
          channel: '#nested',
        },
      },
    }),
    '#nested',
  );
});

test('watchEmailTo reads nested email recipient config', () => {
  assert.equal(
    watchEmailTo({
      trigger_config: {
        email_notify: {
          to: ' alerts@example.test ',
        },
      },
    }),
    'alerts@example.test',
  );
});

test('normalizeWatchCapabilities accepts backend wire names', () => {
  const capabilities = normalizeWatchCapabilities({
    trigger_kinds: ['slack_notify', 'email_notify', 'atc'],
    booking_actions: ['add_to_cart'],
  });

  assert.equal(capabilities.triggerKinds.has('email_notify'), true);
  assert.equal(capabilities.triggerKinds.has('atc'), true);
  assert.equal(capabilities.bookingActions.has('add_to_cart'), true);
});

test('mountWatchEditor renders email, hides Slack channel override, and uses dark surface token', () => {
  const originalDocument = globalThis.document;
  let styleTag = null;
  globalThis.document = {
    getElementById() {
      return null;
    },
    createElement(tagName) {
      assert.equal(tagName, 'style');
      styleTag = { id: '', textContent: '' };
      return styleTag;
    },
    head: {
      appendChild(tag) {
        styleTag = tag;
      },
    },
  };

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
  };

  try {
    const controller = mountWatchEditor(host, {
      title: 'Watch Test',
      capabilities: {
        trigger_kinds: ['slack_notify', 'email_notify'],
        booking_actions: [],
      },
      onSave: async () => {},
    });

    assert.match(host.innerHTML, /name="email_notify"/);
    assert.match(host.innerHTML, />Email</);
    assert.doesNotMatch(host.innerHTML, /name="email_to"/);
    assert.doesNotMatch(host.innerHTML, /Channel override|name="slack_channel"/);
    assert.match(styleTag.textContent, /background: var\(--rt-surface\)/);
    assert.match(styleTag.textContent, /@media \(max-width: 768px\)[\s\S]*\.rt-watch-editor-field input[\s\S]*font-size: 16px/);

    controller.dispose();
  } finally {
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});

test('mountWatchPopover passes email capability into the shared editor', () => {
  const originalDocument = globalThis.document;
  const originalSetTimeout = globalThis.setTimeout;
  let styleTag = null;
  globalThis.document = {
    getElementById() {
      return null;
    },
    createElement(tagName) {
      assert.equal(tagName, 'style');
      styleTag = { id: '', textContent: '' };
      return styleTag;
    },
    head: {
      appendChild(tag) {
        styleTag = tag;
      },
    },
    addEventListener() {},
    removeEventListener() {},
  };
  globalThis.setTimeout = (fn) => {
    fn();
    return 0;
  };

  const host = {
    innerHTML: '',
    addEventListener() {},
    removeEventListener() {},
    contains() {
      return true;
    },
  };

  try {
    const controller = mountWatchPopover(host, {
      poiName: 'Illecillewaet',
      date: '2026-07-18',
      watching: false,
      supportsAddToCart: false,
      watchCapabilities: {
        trigger_kinds: ['slack_notify', 'email_notify'],
        booking_actions: [],
      },
      onSave: async () => {},
      onRemove: async () => {},
      onClose() {},
    });

    assert.match(host.innerHTML, /name="email_notify"/);
    assert.doesNotMatch(host.innerHTML, /Channel override|name="slack_channel"/);

    controller.dispose();
  } finally {
    globalThis.setTimeout = originalSetTimeout;
    if (originalDocument === undefined) {
      delete globalThis.document;
    } else {
      globalThis.document = originalDocument;
    }
  }
});
