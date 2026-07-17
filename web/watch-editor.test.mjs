import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildTriggerPayload,
  normalizeWatchCapabilities,
  watchSlackChannel,
} from './availability/watch-editor.js';

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

test('normalizeWatchCapabilities accepts backend wire names', () => {
  const capabilities = normalizeWatchCapabilities({
    trigger_kinds: ['slack_notify', 'atc'],
    booking_actions: ['add_to_cart'],
  });

  assert.equal(capabilities.triggerKinds.has('atc'), true);
  assert.equal(capabilities.bookingActions.has('add_to_cart'), true);
});
