// Which rows the matrix shows, in what order, and what each cell means.
//
// `cellState` gets the most attention: it decides whether someone is shown a
// booking button, so a wrong answer either hides a real opening or sends a user to
// a booking page for a site that is taken.
import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import type { FusedDay } from './fuse';
import {
  DEFAULT_MATRIX_FILTERS,
  availabilityIndex,
  availableDateCount,
  cellState,
  filterCampsites,
  filterOptions,
  isWatchableKind,
  normalizeFilters,
  rowId,
  siteName,
  siteTitleText,
  sortCampsites,
  sortedCampsites,
} from './matrix-rows';

const site = (id: number, extra: Partial<Campsite> = {}): Partial<Campsite> => ({ id, ...extra });

const day = (
  date: string,
  statuses: Record<string, string>,
  overrides: Partial<FusedDay> = {},
): FusedDay =>
  ({
    date,
    status: 'available',
    campsite_statuses: statuses,
    available_campsite_ids: Object.entries(statuses)
      .filter(([, status]) => status === 'available')
      .map(([id]) => Number(id)),
    ...overrides,
  }) as FusedDay;

describe('naming a site', () => {
  test('prefers the provider name', () => {
    expect(siteName(site(1, { name: 'Bowman 12' }))).toBe('Bowman 12');
  });

  // Aspira's availability map ships resource ids and no names; the number is what
  // is printed on the post at the site, so it is the useful fallback.
  test('falls back to the provider ref, then the id', () => {
    expect(siteName(site(1, { data_provider_ref: '4321' }))).toBe('Site #4321');
    expect(siteName(site(7))).toBe('Site #7');
    expect(siteName({})).toBe('(unknown)');
  });

  test('qualifies the title with the loop when there is one', () => {
    expect(siteTitleText(site(1, { name: 'A12', loop_name: 'Upper Loop' }))).toBe('Upper Loop / A12');
    expect(siteTitleText(site(1, { name: 'A12' }))).toBe('A12');
    // Whitespace-only loops are not loops.
    expect(siteTitleText(site(1, { name: 'A12', loop_name: '   ' }))).toBe('A12');
  });
});

describe('the row set', () => {
  test('orders by loop, then site, numerically', () => {
    const rows = sortedCampsites([
      site(1, { name: 'Site 10', loop_name: 'B' }),
      site(2, { name: 'Site 9', loop_name: 'B' }),
      site(3, { name: 'Site 1', loop_name: 'A' }),
    ]);

    expect(rows.map((row) => row.name)).toEqual(['Site 1', 'Site 9', 'Site 10']);
  });

  // Loop-less rows sort last rather than first: they are the least identifiable.
  test('puts loop-less rows last', () => {
    const rows = sortedCampsites([site(1, { name: 'Zed' }), site(2, { name: 'Aaa', loop_name: 'B' })]);

    expect(rows.map((row) => row.name)).toEqual(['Aaa', 'Zed']);
  });

  // The catalog and the availability window are separate requests. The grid must
  // still draw when the catalog is slower, or failed.
  test('synthesises rows from the days when the catalog is empty', () => {
    const rows = sortedCampsites([], [day('2026-08-10', { 5: 'available', 3: 'reserved' })]);

    expect(rows.map(rowId)).toEqual(['3', '5']);
    expect(siteName(rows[0]!)).toBe('Site #3');
  });

  test('prefers the catalog when it has anything at all', () => {
    const rows = sortedCampsites([site(9, { name: 'Real' })], [day('2026-08-10', { 5: 'available' })]);

    expect(rows.map((row) => row.name)).toEqual(['Real']);
  });
});

describe('filtering', () => {
  const rows = [
    site(1, { name: 'A12', loop_name: 'Upper', kind: 'tent', data_provider_ref: '9001' }),
    site(2, { name: 'B43', loop_name: 'Lower', kind: 'rv', data_provider: 'aspira' }),
  ];

  test('matches the name, the loop, the type and the provider ref', () => {
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: 'a12' })).toHaveLength(1);
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: 'lower' })).toHaveLength(1);
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: 'rv' })).toHaveLength(1);
    // A user typing digits may mean the site number or the provider's ref.
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: '9001' })).toHaveLength(1);
  });

  test('is case- and whitespace-insensitive', () => {
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: '  UPPER  ' })).toHaveLength(1);
  });

  test('the loop and type dropdowns are exact, not substring', () => {
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, loop: 'Upp' })).toHaveLength(0);
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, loop: 'Upper' })).toHaveLength(1);
  });

  test('combines the dropdowns with the query', () => {
    expect(
      filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, loop: 'Upper', query: 'b43' }),
    ).toHaveLength(0);
  });

  test('offers each distinct column value once, numerically ordered', () => {
    expect(
      filterOptions(
        [site(1, { loop_name: 'Loop 10' }), site(2, { loop_name: 'Loop 2' }), site(3, { loop_name: 'Loop 2' }), site(4, {})],
        'loop_name',
      ),
    ).toEqual(['Loop 2', 'Loop 10']);
  });
});

describe('sorting', () => {
  const days = [day('2026-08-10', { 1: 'available', 2: 'reserved' }), day('2026-08-11', { 1: 'available', 2: 'available' })];
  const context = { availabilityByDate: availabilityIndex(days), visibleDays: days };

  test('available-first ranks by how many days are open', () => {
    const rows = sortCampsites([site(2, { name: 'B' }), site(1, { name: 'A' })], 'available', context);

    expect(rows.map((row) => row.name)).toEqual(['A', 'B']);
  });

  test('a tie in openings falls back to loop-then-site', () => {
    // Ids the days say nothing about, so both score zero openings.
    const tied = [site(7, { name: 'Zed', loop_name: 'B' }), site(8, { name: 'Aaa', loop_name: 'A' })];

    expect(sortCampsites(tied, 'available', context).map((row) => row.name)).toEqual(['Aaa', 'Zed']);
  });

  test('site order ignores the loop', () => {
    const rows = sortCampsites(
      [site(1, { name: 'Zed', loop_name: 'A' }), site(2, { name: 'Aaa', loop_name: 'B' })],
      'site',
      context,
    );

    expect(rows.map((row) => row.name)).toEqual(['Aaa', 'Zed']);
  });

  test('type order groups by kind, then loop, then site', () => {
    const rows = sortCampsites(
      [
        site(1, { name: 'B', kind: 'tent' }),
        site(2, { name: 'A', kind: 'rv' }),
        site(3, { name: 'A', kind: 'tent' }),
      ],
      'type',
      context,
    );

    expect(rows.map((row) => [row.kind, row.name])).toEqual([
      ['rv', 'A'],
      ['tent', 'A'],
      ['tent', 'B'],
    ]);
  });

  test('an unknown sort key is coerced to the default', () => {
    expect(normalizeFilters({ sort: 'nonsense' as never }).sort).toBe('available');
    expect(normalizeFilters(null)).toEqual(DEFAULT_MATRIX_FILTERS);
  });
});

describe('counting a row"s open days', () => {
  test('counts explicit availables', () => {
    const days = [day('2026-08-10', { 1: 'available' }), day('2026-08-11', { 1: 'reserved' })];

    expect(
      availableDateCount(site(1), { availabilityByDate: availabilityIndex(days), visibleDays: days }),
    ).toBe(1);
  });

  // The derived id list fills in for a day that carried no per-site statuses...
  test('falls back to the day"s id list when the site has no explicit status', () => {
    const days = [
      day('2026-08-10', {}, { available_campsite_ids: [1], campsite_statuses: {} }),
    ];

    expect(
      availableDateCount(site(1), { availabilityByDate: availabilityIndex(days), visibleDays: days }),
    ).toBe(1);
  });

  // ...but must never override one that said otherwise.
  test('an explicit reserved beats the id list', () => {
    const days = [day('2026-08-10', { 1: 'reserved' }, { available_campsite_ids: [1] })];

    expect(
      availableDateCount(site(1), { availabilityByDate: availabilityIndex(days), visibleDays: days }),
    ).toBe(0);
  });
});

describe('what a cell means', () => {
  const ids = (day: FusedDay) => availabilityIndex([day]).get(day.date);

  test('an explicit status wins', () => {
    const d = day('2026-08-10', { 1: 'first_come' });

    expect(cellState(site(1), d, ids(d)).value).toBe('first_come');
  });

  test('the id list covers a site with no explicit status', () => {
    const d = day('2026-08-10', {}, { available_campsite_ids: [1], campsite_statuses: {} });

    expect(cellState(site(1), d, ids(d)).value).toBe('available');
  });

  // The subtle one: the day rolled up to available, but this site is not in the
  // list of what is open — so it is taken, not open.
  test('a site absent from an available day"s id list reads reserved', () => {
    const d = day('2026-08-10', {}, {
      status: 'available',
      available_campsite_ids: [2],
      campsite_statuses: {},
    });

    expect(cellState(site(1), d, ids(d)).value).toBe('reserved');
  });

  // Without an id list there is nothing to infer from, so the day's own status
  // stands rather than being downgraded to a claim we cannot support.
  test('with no id list at all the day"s status stands', () => {
    const d = day('2026-08-10', {}, {
      status: 'available',
      available_campsite_ids: [],
      campsite_statuses: {},
    });

    expect(cellState(site(1), d, undefined).value).toBe('available');
  });

  test('an unreadable explicit status is unknown, not inherited', () => {
    const d = day('2026-08-10', { 1: 'nonsense' }, { status: 'available', available_campsite_ids: [] });

    expect(cellState(site(1), d, ids(d)).value).toBe('unknown');
  });

  test('a closed day is closed for every row', () => {
    const d = day('2026-08-10', {}, { status: 'closed', available_campsite_ids: [], campsite_statuses: {} });

    expect(cellState(site(1), d, ids(d)).value).toBe('closed');
  });
});

describe('which cells can be watched', () => {
  // Occupied now, but able to open up.
  test('reserved and first-come are watchable', () => {
    expect(isWatchableKind('reserved')).toBe(true);
    expect(isWatchableKind('first-come')).toBe(true);
  });

  // Available is already bookable, and there is nothing to wait for on the rest.
  test('nothing else is', () => {
    for (const kind of ['available', 'closed', 'unknown', 'past']) {
      expect(isWatchableKind(kind)).toBe(false);
    }
  });

  // These are `kind` values, not wire values: `first_come` renders `first-come`,
  // and matching on the wire form would silently disable watches on those cells.
  test('matches the CSS kind, not the wire value', () => {
    expect(isWatchableKind('first_come')).toBe(false);
  });
});
