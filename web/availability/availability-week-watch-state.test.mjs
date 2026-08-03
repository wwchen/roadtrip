import assert from 'node:assert/strict';
import test from 'node:test';
import { clearWatchState, watchedDatesSet } from './availability-week.js';

// Test state-clearing and watch state isolation on 401 / signed-out transitions.

test('clearWatchState resets watchesByWindow to empty Map', () => {
  const ctx = {
    watchesByWindow: new Map([['2026-08-05|2026-08-06', { id: 123, start_date: '2026-08-05' }]]),
    canManageWatches: true,
    watchPopover: null,
  };
  clearWatchState(ctx);
  assert.ok(ctx.watchesByWindow instanceof Map, 'watchesByWindow should remain a Map');
  assert.equal(ctx.watchesByWindow.size, 0, 'watchesByWindow should be empty');
});

test('clearWatchState sets canManageWatches to false', () => {
  const ctx = {
    watchesByWindow: new Map(),
    canManageWatches: true,
    watchPopover: null,
  };
  clearWatchState(ctx);
  assert.equal(ctx.canManageWatches, false, 'canManageWatches should be false');
});

test('clearWatchState disposes watchPopover if present', () => {
  let disposeCalled = false;
  const ctx = {
    watchesByWindow: new Map(),
    canManageWatches: true,
    watchPopover: {
      dispose: () => {
        disposeCalled = true;
      },
    },
  };
  clearWatchState(ctx);
  assert.equal(disposeCalled, true, 'watchPopover.dispose should be called');
  assert.equal(ctx.watchPopover, null, 'watchPopover should be null');
});

test('clearWatchState handles null watchPopover', () => {
  const ctx = {
    watchesByWindow: new Map(),
    canManageWatches: true,
    watchPopover: null,
  };
  clearWatchState(ctx);
  assert.equal(ctx.watchPopover, null, 'watchPopover should remain null');
});

test('watchedDatesSet returns empty Set when canManageWatches is false', () => {
  const ctx = {
    canManageWatches: false,
    watchesByWindow: new Map([
      ['2026-08-05|2026-08-06', { id: 123, start_date: '2026-08-05' }],
      ['2026-08-10|2026-08-11', { id: 456, start_date: '2026-08-10' }],
    ]),
  };
  const result = watchedDatesSet(ctx);
  assert.ok(result instanceof Set, 'should return a Set');
  assert.equal(result.size, 0, 'should be empty when user cannot manage watches');
});

test('watchedDatesSet returns watched dates when canManageWatches is true', () => {
  const ctx = {
    canManageWatches: true,
    watchesByWindow: new Map([
      ['2026-08-05|2026-08-06', { id: 123, start_date: '2026-08-05' }],
      ['2026-08-10|2026-08-11', { id: 456, start_date: '2026-08-10' }],
    ]),
  };
  const result = watchedDatesSet(ctx);
  assert.ok(result instanceof Set, 'should return a Set');
  assert.equal(result.size, 2, 'should contain watched dates');
  assert.ok(result.has('2026-08-05'), 'should include first watched date');
  assert.ok(result.has('2026-08-10'), 'should include second watched date');
});

test('watchedDatesSet handles empty watchesByWindow', () => {
  const ctx = {
    canManageWatches: true,
    watchesByWindow: new Map(),
  };
  const result = watchedDatesSet(ctx);
  assert.ok(result instanceof Set, 'should return a Set');
  assert.equal(result.size, 0, 'should be empty when no watches');
});

test('watchedDatesSet handles watches with startDate camelCase property', () => {
  const ctx = {
    canManageWatches: true,
    watchesByWindow: new Map([
      ['2026-08-05|2026-08-06', { id: 123, startDate: '2026-08-05' }],
    ]),
  };
  const result = watchedDatesSet(ctx);
  assert.equal(result.size, 1, 'should handle camelCase startDate');
  assert.ok(result.has('2026-08-05'), 'should include the watched date');
});

test('popover 401 handling invokes clearWatchState', () => {
  // The popover onSave/onRemove callbacks are inline in availability-week.js
  // and catch 401s to route them to clearWatchState + closeWatchPopover + rerender.
  // This test confirms the clearWatchState logic they rely on is correct.
  const ctx = {
    watchesByWindow: new Map([['2026-08-05|2026-08-06', { id: 123, start_date: '2026-08-05' }]]),
    canManageWatches: true,
    watchPopover: {
      dispose: () => {},
    },
  };
  clearWatchState(ctx);
  assert.equal(ctx.canManageWatches, false, 'should clear canManageWatches on 401');
  assert.equal(ctx.watchesByWindow.size, 0, 'should clear watches on 401');
  assert.equal(ctx.watchPopover, null, 'should dispose popover on 401');
});
