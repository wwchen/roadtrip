import { afterEach, describe, expect, test } from 'vitest';
import type { Watch } from '@/api/watches-api';
import {
  ALERT_PARAM,
  alertName,
  alertRows,
  barLabel,
  byStartDate,
  clearAlertDeepLink,
  countByStatus,
  doneKind,
  readAlertDeepLink,
} from './alert-rows';

const watch = (over: Partial<Watch> = {}): Watch =>
  ({
    id: 1,
    poi_id: 232447,
    campsite_filters: {},
    start_date: '2026-08-10',
    end_date: '2026-08-11',
    trigger_kinds: ['slack_notify'],
    trigger_config: {},
    stop_when_triggered: true,
    status: 'active',
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    ...over,
  }) as Watch;

describe('byStartDate', () => {
  test('sorts soonest first', () => {
    const sorted = [watch({ id: 2, start_date: '2026-09-01' }), watch({ id: 1, start_date: '2026-08-10' })]
      .sort(byStartDate)
      .map((w) => w.id);

    expect(sorted).toEqual([1, 2]);
  });

  test('puts undated watches last', () => {
    const sorted = [watch({ id: 2, start_date: undefined }), watch({ id: 1 })]
      .sort(byStartDate)
      .map((w) => w.id);

    expect(sorted).toEqual([1, 2]);
  });
});

describe('alertRows', () => {
  test('flattens the three status lists into one sorted list', () => {
    const rows = alertRows([
      [watch({ id: 3, start_date: '2026-09-05' })],
      [watch({ id: 1, start_date: '2026-08-01', status: 'paused' })],
      [watch({ id: 2, start_date: '2026-08-20', status: 'done' })],
    ]);

    expect(rows.map((w) => w.id)).toEqual([1, 2, 3]);
  });

  test('tolerates a list that has not arrived', () => {
    expect(alertRows([undefined, [watch()], undefined]).map((w) => w.id)).toEqual([1]);
  });
});

describe('counts and the bar label', () => {
  test('counts by status', () => {
    expect(
      countByStatus([watch(), watch({ status: 'paused' }), watch({ status: 'done' }), watch()]),
    ).toEqual({ active: 2, paused: 1, done: 1, total: 4 });
  });

  test('names only the extras that exist', () => {
    expect(barLabel({ active: 1, paused: 0, done: 0, total: 1 })).toBe('1 availability alert');
    expect(barLabel({ active: 2, paused: 0, done: 0, total: 2 })).toBe('2 availability alerts');
    expect(barLabel({ active: 2, paused: 1, done: 3, total: 6 })).toBe(
      '2 availability alerts · 1 paused · 3 done',
    );
  });

  test('does not count done watches as alerts', () => {
    // A done watch is not being watched anymore — it must not inflate the headline
    // number, which is what `total` (active+paused+done) used to do.
    expect(barLabel({ active: 0, paused: 0, done: 2, total: 2 })).toBe('2 done');
    expect(barLabel({ active: 0, paused: 1, done: 2, total: 3 })).toBe('1 paused · 2 done');
  });
});

describe('alertName', () => {
  test('prefers the POI name', () => {
    expect(alertName(watch(), new Map([[232447, 'Bowman Bay']]))).toBe('Bowman Bay');
  });

  test('falls back to the id while the name is unknown', () => {
    expect(alertName(watch(), new Map())).toBe('POI 232447');
  });

  test('names a campsite-targeted watch from its campsite', () => {
    const site = watch({ poi_id: undefined, campsite: { name: 'Site 4', loop_name: 'Upper' } as never });

    expect(alertName(site, new Map())).toBe('Upper / Site 4');
  });
});

describe('doneKind', () => {
  test('a window that has passed expired', () => {
    expect(doneKind(watch({ end_date: '2026-08-01' }), '2026-08-09')).toBe('expired');
  });

  test('anything else that is done was triggered', () => {
    expect(doneKind(watch({ end_date: '2026-08-20' }), '2026-08-09')).toBe('found');
    expect(doneKind(watch({ end_date: undefined }), '2026-08-09')).toBe('found');
  });
});

describe('the Slack deep link', () => {
  afterEach(() => window.history.replaceState(null, '', '/'));

  test('reads the watch and the action it names', () => {
    expect(readAlertDeepLink('?alert=9&alert_action=pause')).toEqual({
      watchId: '9',
      action: 'pause',
    });
  });

  test('keeps the row but drops an action it does not know', () => {
    expect(readAlertDeepLink('?alert=9&alert_action=explode')).toEqual({
      watchId: '9',
      action: null,
    });
  });

  test('answers null with no alert parameter', () => {
    expect(readAlertDeepLink('?poi=5')).toBeNull();
    expect(readAlertDeepLink('')).toBeNull();
  });

  test('clearing takes both parameters and leaves the rest', () => {
    window.history.replaceState(null, '', '/?alert=9&alert_action=pause&route=abc&poi=5');

    clearAlertDeepLink();

    expect(window.location.search).toBe('?route=abc&poi=5');
  });

  test('clearing is a no-op when there is nothing to clear', () => {
    window.history.replaceState(null, '', '/?route=abc');

    clearAlertDeepLink();

    expect(window.location.search).toBe('?route=abc');
    expect(new URLSearchParams(window.location.search).has(ALERT_PARAM)).toBe(false);
  });
});
