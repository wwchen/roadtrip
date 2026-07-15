import assert from 'node:assert/strict';
import test from 'node:test';

import { renderDayDetail } from './availability/day-detail.js';
import { renderSiteMatrix } from './availability/site-matrix.js';

const RESERVED_DAY = {
  date: '2026-07-04',
  status: 'reserved',
  available_campsite_ids: [],
  campsite_statuses: { 1: 'reserved' },
};

const CAMPSITES = [
  {
    id: 1,
    name: 'A12',
    vendor_id: '100',
  },
];

test('site matrix keeps watched dates visible when new watches are unsupported', () => {
  const html = renderSiteMatrix({
    state: 'success',
    campsites: CAMPSITES,
    days: [RESERVED_DAY],
    siteColumnWidth: 128,
    watchedDates: new Set(['2026-07-04']),
    canCreateWatch: false,
  });

  assert.match(html, /data-watch-date="2026-07-04"/);
  assert.match(html, /cg-site-matrix-cell-watch is-watched/);
  assert.match(html, /availability watch set, tap to manage/);
});

test('site matrix keeps unsupported watch cells clickable for feedback', () => {
  const html = renderSiteMatrix({
    state: 'success',
    campsites: CAMPSITES,
    days: [RESERVED_DAY],
    siteColumnWidth: 128,
    watchedDates: new Set(),
    canCreateWatch: false,
    canOpenWatchPopover: true,
  });

  assert.match(html, /data-watch-date="2026-07-04"/);
  assert.match(html, /cg-site-matrix-cell-watch/);
  assert.match(html, /watch unavailable/);
});

test('day detail keeps existing watches removable when new watches are unsupported', () => {
  const html = renderDayDetail({
    day: RESERVED_DAY,
    watching: true,
    canCreateWatch: false,
  });

  assert.match(html, /Watching - tap to remove/);
  assert.match(html, /data-state="watching"/);
});
