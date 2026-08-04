import assert from 'node:assert/strict';
import test from 'node:test';

import { state } from './core.js';
import {
  bindCursor, rebindLayerHandler,
  UNCATEGORIZED_AGENCY, featureAgency, agenciesInViewport,
  agencyCountsInViewport, campgroundFeaturePassesFilter,
  setAgencyHidden, isAgencyHidden,
  onCampgroundFilterChange, notifyCampgroundFilterChanged,
} from './layers.js';

const cgFc = (agencies) => ({
  type: 'FeatureCollection',
  features: agencies.map((a, i) => ({
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [-121 - i * 0.1, 47] },
    properties: a == null ? { category: 'campground' } : { category: 'campground', agency: a },
  })),
});

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

test('featureAgency falls back to the Uncategorized sentinel', () => {
  assert.equal(featureAgency({ category: 'campground' }), UNCATEGORIZED_AGENCY);
  assert.equal(featureAgency({ category: 'campground', agency: '  ' }), UNCATEGORIZED_AGENCY);
  assert.equal(featureAgency({ category: 'campground', agency: 'BC Parks' }), 'BC Parks');
});

test('agenciesInViewport lists distinct agencies sorted, incl. Uncategorized', () => {
  const list = agenciesInViewport(cgFc(['Ohio State Parks', 'BC Parks', 'BC Parks', null]));
  assert.deepEqual(list, ['BC Parks', 'Ohio State Parks', UNCATEGORIZED_AGENCY]);
});

test('agencyCountsInViewport counts per agency', () => {
  const counts = agencyCountsInViewport(cgFc(['BC Parks', 'BC Parks', null]));
  assert.equal(counts.get('BC Parks'), 2);
  assert.equal(counts.get(UNCATEGORIZED_AGENCY), 1);
});

test('new agencies pass the filter by default; unchecking hides only that one', () => {
  assert.equal(campgroundFeaturePassesFilter({ category: 'campground', agency: 'BC Parks' }), true);
  setAgencyHidden('BC Parks', true);
  assert.equal(isAgencyHidden('BC Parks'), true);
  assert.equal(campgroundFeaturePassesFilter({ category: 'campground', agency: 'BC Parks' }), false);
  assert.equal(campgroundFeaturePassesFilter({ category: 'campground', agency: 'Ohio State Parks' }), true);
  setAgencyHidden('BC Parks', false); // reset for other tests
});

test('notifyCampgroundFilterChanged fires registered listeners once, unsub stops them', () => {
  let calls = 0;
  const off = onCampgroundFilterChange(() => { calls += 1; });
  notifyCampgroundFilterChanged();
  assert.equal(calls, 1);
  off();
  notifyCampgroundFilterChanged();
  assert.equal(calls, 1); // no further calls after unsubscribe
});

// Minimal single-element DOM stub: renderCampgroundLegend only needs
// getElementById(host) and to set/read innerHTML + query the rendered rows.
function stubLegendHost() {
  const host = {
    id: 'cg-agency-legend',
    innerHTML: '',
    get textContent() { return this.innerHTML.replace(/<[^>]+>/g, ' '); },
    querySelectorAll(sel) {
      if (sel === 'label') return this.innerHTML.match(/<label\b/g) || [];
      return [];
    },
  };
  const previous = globalThis.document;
  globalThis.document = { getElementById: (id) => (id === 'cg-agency-legend' ? host : null) };
  return {
    host,
    restore() {
      if (previous === undefined) delete globalThis.document;
      else globalThis.document = previous;
    },
  };
}

test('renderCampgroundLegend renders one checked row per viewport agency with counts', async () => {
  const { renderCampgroundLegend } = await import('./layers.js');
  const { host, restore } = stubLegendHost();
  try {
    renderCampgroundLegend(cgFc(['BC Parks', 'BC Parks', 'Ohio State Parks', null]));
    assert.equal(host.querySelectorAll('label').length, 3); // BC Parks, Ohio State Parks, Uncategorized
    assert.match(host.innerHTML, /data-cg-agency="BC Parks"[^>]* checked/);
    assert.match(host.textContent, /BC Parks/);
    assert.match(host.textContent, /Ohio State Parks/);
    assert.match(host.textContent, new RegExp(UNCATEGORIZED_AGENCY));
    assert.match(host.innerHTML, /\(2\)/); // BC Parks count
  } finally {
    restore();
  }
});
