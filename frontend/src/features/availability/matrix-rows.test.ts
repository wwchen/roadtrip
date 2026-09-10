import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import type { AvailabilityCell, AvailabilityDay } from '@/api/availability-api';
import {
  DEFAULT_MATRIX_FILTERS,
  availableDateCount,
  cellState,
  filterCampsites,
  loopOptions,
  normalizeFilters,
  rowId,
  siteName,
  siteTitleText,
  sortCampsites,
  sortedCampsites,
  typeOptions,
} from './matrix-rows';

const site = (id: number, extra: Partial<Campsite> = {}): Partial<Campsite> => ({ id, ...extra });

/** A fused day: `cells` is what the endpoint sends and the only thing read here. */
const day = (
  date: string,
  cells: Record<string, AvailabilityCell>,
  overrides: Partial<AvailabilityDay> = {},
): AvailabilityDay => ({
  date,
  status: 'available',
  watchable: Object.values(cells).some((cell) => cell.watchable),
  cells,
  ...overrides,
});

/** The two cells every case below is built from. */
const open: AvailabilityCell = { status: 'available', watchable: false };
const taken: AvailabilityCell = { status: 'reserved', watchable: true };

describe('naming a site', () => {
  test('prefers the provider name', () => {
    expect(siteName(site(1, { name: 'Bowman 12' }))).toBe('Bowman 12');
  });

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

  test('puts loop-less rows last', () => {
    const rows = sortedCampsites([site(1, { name: 'Zed' }), site(2, { name: 'Aaa', loop_name: 'B' })]);

    expect(rows.map((row) => row.name)).toEqual(['Aaa', 'Zed']);
  });

  test('synthesises rows from the days" cells when the catalog is empty', () => {
    const rows = sortedCampsites([], [day('2026-08-10', { 5: open, 3: taken })]);

    expect(rows.map(rowId)).toEqual(['3', '5']);
    expect(siteName(rows[0]!)).toBe('Site #3');
  });

  test('prefers the catalog when it has anything at all', () => {
    const rows = sortedCampsites([site(9, { name: 'Real' })], [day('2026-08-10', { 5: open })]);

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

  test('matches the label even when the wire value reads differently', () => {
    const labeled = [site(9, { kind: 'walk_in', kind_label: 'Walk-in' })];

    expect(filterCampsites(labeled, { ...DEFAULT_MATRIX_FILTERS, query: 'walk-in' })).toHaveLength(1);
  });

  test('offers each distinct loop once, numerically ordered', () => {
    expect(
      loopOptions([
        site(1, { loop_name: 'Loop 10' }),
        site(2, { loop_name: 'Loop 2' }),
        site(3, { loop_name: 'Loop 2' }),
        site(4, {}),
      ]),
    ).toEqual(['Loop 2', 'Loop 10']);
  });

  test('offers each distinct type once as a wire value with its label, label-ordered', () => {
    expect(
      typeOptions([
        site(1, { kind: 'walk_in', kind_label: 'Walk-in' }),
        site(2, { kind: 'tent', kind_label: 'Tent' }),
        site(3, { kind: 'tent', kind_label: 'Tent' }),
        site(4, {}),
      ]),
    ).toEqual([
      { value: 'tent', label: 'Tent' },
      { value: 'walk_in', label: 'Walk-in' },
    ]);
  });

  test('never invents a label, and never shows the wire value, for a row that carries none', () => {
    expect(typeOptions([site(1, { kind: 'tent' })])).toEqual([]);
  });
});

describe('sorting', () => {
  const days = [
    day('2026-08-10', { 1: open, 2: taken }),
    day('2026-08-11', { 1: open, 2: open }),
  ];

  test('available-first ranks by how many days are open', () => {
    const rows = sortCampsites([site(2, { name: 'B' }), site(1, { name: 'A' })], 'available', days);

    expect(rows.map((row) => row.name)).toEqual(['A', 'B']);
  });

  test('a tie in openings falls back to loop-then-site', () => {
    // Ids the days say nothing about, so both score zero openings.
    const tied = [site(7, { name: 'Zed', loop_name: 'B' }), site(8, { name: 'Aaa', loop_name: 'A' })];

    expect(sortCampsites(tied, 'available', days).map((row) => row.name)).toEqual(['Aaa', 'Zed']);
  });

  test('site order ignores the loop', () => {
    const rows = sortCampsites(
      [site(1, { name: 'Zed', loop_name: 'A' }), site(2, { name: 'Aaa', loop_name: 'B' })],
      'site',
      days,
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
      days,
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
  test('counts the days whose cell for this row is available', () => {
    const days = [day('2026-08-10', { 1: open }), day('2026-08-11', { 1: taken })];

    expect(availableDateCount(site(1), days)).toBe(1);
  });

  test('a day with no cell for the row counts as nothing, not as the rollup', () => {
    // The day rolled up to `available` on someone else's cell.
    const days = [day('2026-08-10', { 2: open })];

    expect(availableDateCount(site(1), days)).toBe(0);
  });
});

describe('what a cell means', () => {
  test('is the cell the day carries for that row', () => {
    const d = day('2026-08-10', { 1: { status: 'first_come', watchable: true } });

    expect(cellState(site(1), d)).toEqual({ status: 'first_come', watchable: true });
  });

  test('a row the day has no cell for is unknown and unwatchable', () => {
    // Rolled up to `available`, which says nothing about site 1.
    const d = day('2026-08-10', { 2: open });

    expect(cellState(site(1), d)).toEqual({ status: 'unknown', watchable: false });
  });

  test('the rollup never overrides a cell', () => {
    const d = day('2026-08-10', { 1: taken }, { status: 'available' });

    expect(cellState(site(1), d).status).toBe('reserved');
  });

  test('watchable is the backend’s answer, not one derived from the status', () => {
    // Same status, different answer: this provider cannot be internally polled.
    const d = day('2026-08-10', { 1: { status: 'reserved', watchable: false } });

    expect(cellState(site(1), d).watchable).toBe(false);
  });
});
