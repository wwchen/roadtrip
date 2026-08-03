import assert from 'node:assert/strict';
import test from 'node:test';

// Test pure helper for auth-gating logic: given capability + auth state, should
// we show watch CTAs? The modules (site-matrix, day-detail) are not unit-testable
// without a DOM, so we test the gate logic in isolation.

function canWatchMatrix(supportsAlerts, canManageWatches) {
  return supportsAlerts && canManageWatches;
}

function canWatchDayDetail(poiId, hasTriggerKind, canManageWatches) {
  return poiId != null && hasTriggerKind && canManageWatches;
}

test('canWatchMatrix returns false when user cannot manage watches (signed out)', () => {
  assert.equal(canWatchMatrix(true, false), false);
});

test('canWatchMatrix returns false when provider does not support alerts', () => {
  assert.equal(canWatchMatrix(false, true), false);
});

test('canWatchMatrix returns true when both conditions met', () => {
  assert.equal(canWatchMatrix(true, true), true);
});

test('canWatchDayDetail returns false when user cannot manage watches', () => {
  assert.equal(canWatchDayDetail(123, true, false), false);
});

test('canWatchDayDetail returns false when no poiId', () => {
  assert.equal(canWatchDayDetail(null, true, true), false);
});

test('canWatchDayDetail returns false when trigger kind unavailable', () => {
  assert.equal(canWatchDayDetail(123, false, true), false);
});

test('canWatchDayDetail returns true when all conditions met', () => {
  assert.equal(canWatchDayDetail(123, true, true), true);
});
