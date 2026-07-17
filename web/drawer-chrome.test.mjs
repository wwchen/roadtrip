import assert from 'node:assert/strict';
import test from 'node:test';

import { attachDragHandlers } from './drawer/chrome.js';

function makeDrawerHarness() {
  const listeners = {};
  const handleListeners = {};
  const classes = new Set();
  const handle = {
    contains(target) {
      return target === handle;
    },
    addEventListener(type, fn) {
      handleListeners[type] = fn;
    },
  };
  const root = {
    dataset: {},
    scrollTop: 0,
    style: {},
    classList: {
      add(name) {
        classes.add(name);
      },
      remove(name) {
        classes.delete(name);
      },
      contains(name) {
        return classes.has(name);
      },
    },
    querySelector(selector) {
      return selector === '.cg-drawer-handle' ? handle : null;
    },
    getBoundingClientRect() {
      return { height: 600 };
    },
    addEventListener(type, fn) {
      listeners[type] = fn;
    },
  };
  attachDragHandlers(root);
  return { root, handle, listeners, handleListeners };
}

function touchEvent({ target, x = 0, y = 0, cancelable = true } = {}) {
  let prevented = false;
  return {
    event: {
      target,
      touches: [{ clientX: x, clientY: y }],
      changedTouches: [{ clientX: x, clientY: y }],
      cancelable,
      preventDefault() {
        prevented = true;
      },
    },
    wasPrevented() {
      return prevented;
    },
  };
}

test('drawer drag does not claim touch movement that starts on an input', () => {
  const { root, listeners } = makeDrawerHarness();
  const inputTarget = {
    closest(selector) {
      return selector.includes('input') ? inputTarget : null;
    },
  };

  listeners.touchstart(touchEvent({ target: inputTarget }).event);
  const move = touchEvent({ target: inputTarget, y: 20 });
  listeners.touchmove(move.event);

  assert.equal(move.wasPrevented(), false);
  assert.equal(root.style.height, undefined);
});

test('drawer handle still owns vertical drag movement', () => {
  const { root, handle, listeners, handleListeners } = makeDrawerHarness();

  handleListeners.touchstart(touchEvent({ target: handle }).event);
  const move = touchEvent({ target: handle, y: 30 });
  listeners.touchmove(move.event);

  assert.equal(move.wasPrevented(), true);
  assert.equal(root.style.height, '570px');
});
