import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildWatchPayload,
  normalizeWatchCapabilities,
  supportsWatchAlerts,
} from './availability/availability-week.js';

const EXPECTED_DEFAULT_WATCH_CADENCE_SEC = 60;

function ctxWithCapabilities(value) {
  return {
    poiId: 42,
    watchCapabilities: normalizeWatchCapabilities(value),
  };
}

test('watch capability normalization does not invent Slack support', () => {
  const ctx = ctxWithCapabilities({ trigger_kinds: [], booking_actions: [] });

  assert.equal(supportsWatchAlerts(ctx), false);
  assert.equal(ctx.watchCapabilities.triggerKinds.has('slack_notify'), false);
});

test('watch payload creation rejects unsupported watch alerts', () => {
  const ctx = ctxWithCapabilities({ trigger_kinds: [], booking_actions: [] });

  assert.throws(
    () => buildWatchPayload(ctx, '2026-07-04', '2026-07-05'),
    /not available for this campground/,
  );
});

test('watch payload creation uses advertised Slack and ATC capabilities', () => {
  const ctx = ctxWithCapabilities({
    trigger_kinds: ['slack_notify', 'atc'],
    booking_actions: ['add_to_cart'],
  });

  const payload = buildWatchPayload(ctx, '2026-07-04', '2026-07-05', {
    stopWhenFound: false,
    addToCart: true,
  });

  assert.deepEqual(payload.trigger_kinds, ['slack_notify', 'atc']);
  assert.equal(payload.cadence_sec, EXPECTED_DEFAULT_WATCH_CADENCE_SEC);
  assert.equal(payload.stop_when_triggered, false);
});
