// The fusion rules. Every cell, count and banner in the availability UI is a
// function of what comes out of this module, so it gets the closest reading.
import { describe, expect, test } from 'vitest';
import type { CampsiteAvailability } from '@/api/availability-api';
import {
  enumerateDates,
  fuseDay,
  fusePoiCampsitesAvailability,
  oldestCacheBlock,
  rollupStatus,
} from './fuse';

const cache = (ageSeconds: number) => ({ hit: true, age_seconds: ageSeconds, ttl_seconds: 600 });

/** One campsite's stream, as the endpoint ships it. */
const stream = (
  campsiteId: number,
  days: Array<[string, string]>,
  extra: Partial<CampsiteAvailability> = {},
): CampsiteAvailability =>
  ({
    provider: 'recgov',
    campsite_id: campsiteId,
    checked_at: '2026-08-09T00:00:00Z',
    start_date: '2026-08-10',
    end_date: '2026-08-17',
    state: 'ok',
    season: null,
    availability: days.map(([date, status]) => ({ date, status })),
    cache: cache(60),
    ...extra,
  }) as CampsiteAvailability;

describe('rollupStatus', () => {
  test('one bookable site makes the day bookable', () => {
    expect(rollupStatus(['reserved', 'closed', 'available'])).toBe('available');
  });

  test('first-come outranks everything except available', () => {
    expect(rollupStatus(['reserved', 'first_come', 'unknown'])).toBe('first_come');
  });

  // The precedence that matters most: a stream we could not read must not render
  // as a confident "full", because "?" sends the user to check and "R" does not.
  test('unknown outranks reserved', () => {
    expect(rollupStatus(['reserved', 'unknown'])).toBe('unknown');
  });

  test('reserved wins only when every other site is closed', () => {
    expect(rollupStatus(['closed', 'reserved', 'closed'])).toBe('reserved');
  });

  // Unanimity, so one open site keeps the campground open.
  test('closed needs every site to be closed', () => {
    expect(rollupStatus(['closed', 'closed'])).toBe('closed');
    expect(rollupStatus(['closed', 'reserved'])).not.toBe('closed');
  });

  test('no sites at all is unknown, not closed', () => {
    expect(rollupStatus([])).toBe('unknown');
  });

  // The documented deviation: the original compared provider strings verbatim,
  // so casing changed the answer here and nowhere else.
  test('normalises before comparing, unlike the original', () => {
    expect(rollupStatus(['Available'])).toBe('available');
    expect(rollupStatus(['gibberish'])).toBe('unknown');
  });
});

describe('enumerateDates', () => {
  test('is end-exclusive, matching the booking window', () => {
    expect(enumerateDates('2026-08-10', '2026-08-13')).toEqual([
      '2026-08-10',
      '2026-08-11',
      '2026-08-12',
    ]);
  });

  test('spans a month boundary', () => {
    expect(enumerateDates('2026-08-30', '2026-09-02')).toEqual([
      '2026-08-30',
      '2026-08-31',
      '2026-09-01',
    ]);
  });

  // A DST spring-forward day is 23 hours long; date arithmetic that adds
  // milliseconds skips a date here. `local-date` does not, and this pins it.
  test('does not skip a day across a DST transition', () => {
    expect(enumerateDates('2026-03-07', '2026-03-10')).toEqual([
      '2026-03-07',
      '2026-03-08',
      '2026-03-09',
    ]);
  });

  test('an inverted window yields nothing rather than looping', () => {
    expect(enumerateDates('2026-08-13', '2026-08-10')).toEqual([]);
  });
});

describe('oldestCacheBlock', () => {
  // The grid shows one freshness pill, so the honest number is the stalest.
  test('picks the stalest block across the streams', () => {
    const picked = oldestCacheBlock([
      stream(1, [], { cache: cache(30) }),
      stream(2, [], { cache: cache(900) }),
      stream(3, [], { cache: cache(120) }),
    ]);

    expect(picked?.age_seconds).toBe(900);
  });

  test('is null when no stream reported a cache', () => {
    expect(oldestCacheBlock([stream(1, [], { cache: undefined as never })])).toBeNull();
  });
});

describe('fuseDay', () => {
  test('collects each campsite status for the date', () => {
    const day = fuseDay('2026-08-11', [
      stream(7, [['2026-08-10', 'available'], ['2026-08-11', 'reserved']]),
      stream(3, [['2026-08-11', 'available']]),
    ]);

    expect(day.campsite_statuses).toEqual({ 3: 'available', 7: 'reserved' });
    expect(day.available_campsite_ids).toEqual([3]);
    expect(day.status).toBe('available');
  });

  // Numeric, not lexical: with string keys "10" sorts before "9", and the matrix
  // and site list both iterate this order.
  test('orders campsites numerically', () => {
    const day = fuseDay('2026-08-11', [
      stream(10, [['2026-08-11', 'available']]),
      stream(9, [['2026-08-11', 'available']]),
      stream(100, [['2026-08-11', 'available']]),
    ]);

    expect(Object.keys(day.campsite_statuses)).toEqual(['9', '10', '100']);
    expect(day.available_campsite_ids).toEqual([9, 10, 100]);
  });

  // A stream that simply has no row for the date is not the same as one that
  // reported "closed" — it is unknown, and rolls up as such.
  test('a campsite with no row for the date is unknown', () => {
    const day = fuseDay('2026-08-11', [stream(7, [['2026-08-10', 'available']])]);

    expect(day.campsite_statuses).toEqual({ 7: 'unknown' });
    expect(day.status).toBe('unknown');
  });

  test('skips a stream with no campsite id rather than keying on null', () => {
    const day = fuseDay('2026-08-11', [
      stream(7, [['2026-08-11', 'available']]),
      stream(0, [['2026-08-11', 'reserved']], { campsite_id: null }),
    ]);

    expect(day.campsite_statuses).toEqual({ 7: 'available' });
  });

  test('tolerates a missing availability array', () => {
    const day = fuseDay('2026-08-11', [
      stream(7, [], { availability: undefined as never }),
    ]);

    expect(day.campsite_statuses).toEqual({ 7: 'unknown' });
  });
});

describe('fusePoiCampsitesAvailability', () => {
  const window = ['2026-08-10', '2026-08-13'] as const;

  test('produces one day per date in the window', () => {
    const fused = fusePoiCampsitesAvailability(
      {
        campsites: [
          stream(1, [
            ['2026-08-10', 'available'],
            ['2026-08-11', 'reserved'],
            ['2026-08-12', 'closed'],
          ]),
        ],
      },
      ...window,
    );

    expect(fused.state).toBe('success');
    expect(fused.days.map((day) => [day.date, day.status])).toEqual([
      ['2026-08-10', 'available'],
      ['2026-08-11', 'reserved'],
      ['2026-08-12', 'closed'],
    ]);
  });

  // "No online-bookable sites" is permanent; "come back in May" is not. They stay
  // different states because they need different copy.
  test('no campsites at all is empty, not closed for season', () => {
    const fused = fusePoiCampsitesAvailability({ campsites: [] }, ...window);

    expect(fused).toEqual({ state: 'empty', days: [], season: null, cacheBlock: null });
  });

  test('a missing body is empty rather than a throw', () => {
    expect(fusePoiCampsitesAvailability(null, ...window).state).toBe('empty');
    expect(fusePoiCampsitesAvailability({}, ...window).state).toBe('empty');
  });

  test('every campsite closed for season closes the week', () => {
    const fused = fusePoiCampsitesAvailability(
      {
        campsites: [
          stream(1, [], { state: 'closed_for_season' }),
          stream(2, [], { state: 'closed_for_season', season: { reopens_on: '2027-05-01' } }),
        ],
      },
      ...window,
    );

    expect(fused.state).toBe('closed_for_season');
    expect(fused.days).toEqual([]);
    // Carried from whichever stream had it: providers populate the reopen date on
    // some streams and not others.
    expect(fused.season?.reopens_on).toBe('2027-05-01');
  });

  test('one open campsite keeps the week open', () => {
    const fused = fusePoiCampsitesAvailability(
      {
        campsites: [
          stream(1, [], { state: 'closed_for_season' }),
          stream(2, [['2026-08-10', 'available']]),
        ],
      },
      ...window,
    );

    expect(fused.state).toBe('success');
  });

  test('a closed-for-season week with no reopen date carries no season block', () => {
    const fused = fusePoiCampsitesAvailability(
      { campsites: [stream(1, [], { state: 'closed_for_season', season: {} })] },
      ...window,
    );

    expect(fused.state).toBe('closed_for_season');
    expect(fused.season).toBeNull();
  });

  test('carries the freshness block through on both non-empty states', () => {
    const open = fusePoiCampsitesAvailability(
      { campsites: [stream(1, [['2026-08-10', 'available']], { cache: cache(300) })] },
      ...window,
    );
    const closed = fusePoiCampsitesAvailability(
      { campsites: [stream(1, [], { state: 'closed_for_season', cache: cache(300) })] },
      ...window,
    );

    expect(open.cacheBlock?.age_seconds).toBe(300);
    expect(closed.cacheBlock?.age_seconds).toBe(300);
  });
});
