import { describe, expect, test } from 'vitest';
import type { Watch } from '@/api/watches-api';
import {
  NO_WATCH_CAPABILITIES,
  indexWatchesByWindow,
  normalizeWatchCapabilities,
  stayEndDate,
  supportsAddToCart,
  supportsWatchAlerts,
  watchWindowKey,
  watchedDates,
} from './watch-windows';

const watch = (overrides: Partial<Watch> = {}): Watch =>
  ({
    id: 1,
    targets: [{ poi_id: 42 }],
    poi_id: 42,
    campsite_filters: {},
    start_date: '2026-08-11',
    end_date: '2026-08-12',
    trigger_kinds: ['slack_notify'],
    trigger_config: {},
    stop_when_triggered: true,
    status: 'active',
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    ...overrides,
  }) as Watch;

describe('capabilities', () => {
  test('reads the wire arrays into sets', () => {
    const caps = normalizeWatchCapabilities({
      trigger_kinds: ['slack_notify', 'atc'],
      booking_actions: ['add_to_cart'],
    });

    expect([...caps.triggerKinds]).toEqual(['slack_notify', 'atc']);
    expect(caps.bookingActions.has('add_to_cart')).toBe(true);
  });

  test('passes an already-normalised value through', () => {
    const caps = normalizeWatchCapabilities({
      triggerKinds: new Set(['email_notify']),
      bookingActions: new Set(),
    });

    expect(caps.triggerKinds.has('email_notify')).toBe(true);
  });

  test('a missing block grants nothing', () => {
    expect(normalizeWatchCapabilities(null)).toEqual(NO_WATCH_CAPABILITIES);
    expect(normalizeWatchCapabilities({} as never).triggerKinds.size).toBe(0);
  });

  test('either notify channel means alerts are supported', () => {
    expect(supportsWatchAlerts(normalizeWatchCapabilities({ trigger_kinds: ['slack_notify'], booking_actions: [] }))).toBe(true);
    expect(supportsWatchAlerts(normalizeWatchCapabilities({ trigger_kinds: ['email_notify'], booking_actions: [] }))).toBe(true);
    expect(supportsWatchAlerts(NO_WATCH_CAPABILITIES)).toBe(false);
  });

  test('add to cart needs both the action and the trigger', () => {
    const both = normalizeWatchCapabilities({ trigger_kinds: ['atc'], booking_actions: ['add_to_cart'] });
    const actionOnly = normalizeWatchCapabilities({ trigger_kinds: [], booking_actions: ['add_to_cart'] });
    const triggerOnly = normalizeWatchCapabilities({ trigger_kinds: ['atc'], booking_actions: [] });

    expect(supportsAddToCart(both)).toBe(true);
    expect(supportsAddToCart(actionOnly)).toBe(false);
    expect(supportsAddToCart(triggerOnly)).toBe(false);
  });

  test('a cart-only provider still supports no alerts', () => {
    expect(supportsWatchAlerts(normalizeWatchCapabilities({ trigger_kinds: ['atc'], booking_actions: ['add_to_cart'] }))).toBe(false);
  });
});

describe('the watch window', () => {
  test('is the single night starting on the tapped day', () => {
    expect(stayEndDate('2026-08-11')).toBe('2026-08-12');
  });

  test('rolls over a month and a year', () => {
    expect(stayEndDate('2026-08-31')).toBe('2026-09-01');
    expect(stayEndDate('2026-12-31')).toBe('2027-01-01');
  });

  test('survives a DST transition', () => {
    expect(stayEndDate('2026-03-08')).toBe('2026-03-09');
  });

  test('keys on the exact window', () => {
    expect(watchWindowKey('2026-08-11', '2026-08-12')).toBe('2026-08-11|2026-08-12');
  });
});

describe('indexing the user"s watches', () => {
  test('keys each watch by its window', () => {
    const index = indexWatchesByWindow([watch()], 42);

    expect(index.get('2026-08-11|2026-08-12')?.id).toBe(1);
  });

  test('drops watches that are done', () => {
    expect(indexWatchesByWindow([watch({ status: 'done' })], 42).size).toBe(0);
  });

  test('keeps paused watches, which are still the user"s', () => {
    expect(indexWatchesByWindow([watch({ status: 'paused' })], 42).size).toBe(1);
  });

  test('ignores watches for another POI', () => {
    expect(indexWatchesByWindow([watch({ poi_id: 99 })], 42).size).toBe(0);
  });

  test('ignores a watch with an incomplete window', () => {
    expect(indexWatchesByWindow([watch({ start_date: '' })], 42).size).toBe(0);
    expect(indexWatchesByWindow([watch({ end_date: undefined as never })], 42).size).toBe(0);
  });

  test('a missing list is an empty index, not a throw', () => {
    expect(indexWatchesByWindow(null, 42).size).toBe(0);
    expect(indexWatchesByWindow(undefined, 42).size).toBe(0);
  });

  test('matches a numeric POI id against a string one', () => {
    expect(indexWatchesByWindow([watch({ poi_id: 42 })], '42').size).toBe(1);
  });

  test('reduces to the set of watched days', () => {
    const index = indexWatchesByWindow(
      [watch(), watch({ id: 2, start_date: '2026-08-14', end_date: '2026-08-15' })],
      42,
    );

    expect([...watchedDates(index)].sort()).toEqual(['2026-08-11', '2026-08-14']);
  });
});
