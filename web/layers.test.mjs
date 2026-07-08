import assert from 'node:assert/strict';
import test from 'node:test';

import { state } from './core.js';
import { bindCursor, rebindLayerHandler } from './layers.js';

function fakeMap() {
  const listeners = new Map();
  const canvas = { style: { cursor: '' } };
  const key = (type, layerId) => `${type}:${layerId}`;
  return {
    on(type, layerId, handler) {
      const k = key(type, layerId);
      listeners.set(k, [...(listeners.get(k) || []), handler]);
    },
    off(type, layerId, handler) {
      const k = key(type, layerId);
      listeners.set(k, (listeners.get(k) || []).filter(h => h !== handler));
    },
    fireLayer(type, layerId, event = {}) {
      for (const handler of listeners.get(key(type, layerId)) || []) handler(event);
    },
    listenerCount(type, layerId) {
      return (listeners.get(key(type, layerId)) || []).length;
    },
    getCanvas() {
      return canvas;
    },
  };
}

function withFakeMap(fn) {
  const previous = state.map;
  state.map = fakeMap();
  try {
    fn(state.map);
  } finally {
    state.map = previous;
  }
}

test('rebindLayerHandler replaces the existing layer listener', () => {
  withFakeMap(map => {
    let calls = 0;
    const handler = () => { calls += 1; };

    rebindLayerHandler('click', 'cg-points-hit', handler);
    rebindLayerHandler('click', 'cg-points-hit', handler);

    assert.equal(map.listenerCount('click', 'cg-points-hit'), 1);
    map.fireLayer('click', 'cg-points-hit');
    assert.equal(calls, 1);
  });
});

test('bindCursor keeps one enter and leave handler per layer', () => {
  withFakeMap(map => {
    bindCursor('cg-points-hit');
    bindCursor('cg-points-hit');

    assert.equal(map.listenerCount('mouseenter', 'cg-points-hit'), 1);
    assert.equal(map.listenerCount('mouseleave', 'cg-points-hit'), 1);

    map.fireLayer('mouseenter', 'cg-points-hit');
    assert.equal(map.getCanvas().style.cursor, 'pointer');
    map.fireLayer('mouseleave', 'cg-points-hit');
    assert.equal(map.getCanvas().style.cursor, '');
  });
});
