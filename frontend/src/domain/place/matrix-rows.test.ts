import { describe, expect, test } from 'vitest';
import type { AvailabilityCell, AvailabilityDay } from '@/api/availability-api';
import type { Campsite } from '@/api/campsite-api';
import {
  DEFAULT_MATRIX_FILTERS,
  availableDateCount,
  cellState,
  filterCampsites,
  loopOptions,
  matrixColumns,
  normalizeFilters,
  rowId,
  siteName,
  siteTitleText,
  sortCampsites,
  sortedCampsites,
  typeOptions,
} from './matrix-rows';

const site = (id: number, extra: Partial<Campsite> = {}): Partial<Campsite> => ({ id, ...extra });

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

const open: AvailabilityCell = { status: 'available', watchable: false };
const taken: AvailabilityCell = { status: 'reserved', watchable: true };

const FIRST = '2026-08-10';
const SECOND = '2026-08-11';

const columnsOf = (...days: AvailabilityDay[]) =>
  matrixColumns(
    days.map((d) => d.date),
    days,
  );

describe('naming a site', () => {
  test('prefers the provider name', () => {
    expect(siteName(site(1, { name: 'Bowman 12' }))).toBe('Bowman 12');
  });

  test('falls back to the provider ref, then the id', () => {
    expect(siteName(site(1, { data_provider_ref: '7781' }))).toBe('Site #7781');
    expect(siteName(site(9))).toBe('Site #9');
  });

  test('qualifies the title with the loop when there is one', () => {
    expect(siteTitleText(site(1, { name: '12', loop_name: 'Loop A' }))).toBe('Loop A / 12');
    expect(siteTitleText(site(1, { name: '12' }))).toBe('12');
    expect(rowId(site(4))).toBe('4');
  });

  test('a site the provider only numbered reads as the number on the post', () => {
    expect(siteTitleText({ data_provider_ref: '4711' })).toBe('Site #4711');
  });

  test('the loop qualifies a numbered site too, not only a named one', () => {
    expect(siteTitleText({ loop_name: 'Loop A', data_provider_ref: '4711' })).toBe('Loop A / Site #4711');
    expect(siteTitleText({ loop_name: 'Loop A', id: 4711 })).toBe('Loop A / Site #4711');
  });

  test('a site with nothing to call it is not named at all, and a loop does not name it', () => {
    expect(siteTitleText({})).toBe('');
    expect(siteTitleText({ loop_name: 'Loop A' })).toBe('');
  });

  test('trims what the provider padded', () => {
    expect(siteTitleText({ name: '  Walk-in 3  ', loop_name: '  Loop B  ' })).toBe('Loop B / Walk-in 3');
  });
});

describe('the row set', () => {
  test('orders by loop, then site, numerically', () => {
    const rows = sortedCampsites([
      site(1, { name: '10', loop_name: 'Loop B' }),
      site(2, { name: '9', loop_name: 'Loop B' }),
      site(3, { name: '1', loop_name: 'Loop A' }),
    ]);

    expect(rows.map(siteName)).toEqual(['1', '9', '10']);
  });

  test('puts loop-less rows last', () => {
    const rows = sortedCampsites([site(1, { name: 'No loop' }), site(2, { name: 'A', loop_name: 'Loop A' })]);

    expect(rows.map(siteName)).toEqual(['A', 'No loop']);
  });

  test("synthesises rows from the columns' cells when the catalog is empty", () => {
    const rows = sortedCampsites([], columnsOf(day(FIRST, { 5: open, 6: taken })));

    expect(rows.map(rowId)).toEqual(['5', '6']);
  });

  test('prefers the catalog when it has anything at all', () => {
    const rows = sortedCampsites([site(9, { name: 'Only' })], columnsOf(day(FIRST, { 5: open })));

    expect(rows.map(rowId)).toEqual(['9']);
  });
});

describe('filtering', () => {
  const rows = [
    site(1, { name: '12', loop_name: 'Loop A', kind: 'tent', kind_label: 'Tent', data_provider_ref: '401' }),
    site(2, { name: '14', loop_name: 'Loop B', kind: 'rv', kind_label: 'RV', data_provider_ref: '402' }),
  ];

  test('matches the name, the loop, the type and the provider ref', () => {
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: 'Loop B' }).map(rowId)).toEqual(['2']);
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: '401' }).map(rowId)).toEqual(['1']);
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: 'tent' }).map(rowId)).toEqual(['1']);
  });

  test('is case- and whitespace-insensitive', () => {
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: '  loop a  ' }).map(rowId)).toEqual(['1']);
  });

  test('the loop and type dropdowns are exact, not substring', () => {
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, loop: 'Loop' })).toEqual([]);
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, type: 'rv' }).map(rowId)).toEqual(['2']);
  });

  test('combines the dropdowns with the query', () => {
    expect(
      filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, loop: 'Loop A', query: '14' }),
    ).toEqual([]);
  });

  test('matches the label even when the wire value reads differently', () => {
    expect(filterCampsites(rows, { ...DEFAULT_MATRIX_FILTERS, query: 'RV' }).map(rowId)).toEqual(['2']);
  });

  test('offers each distinct loop once, numerically ordered', () => {
    expect(
      loopOptions([
        site(1, { loop_name: 'Loop 10' }),
        site(2, { loop_name: 'Loop 9' }),
        site(3, { loop_name: 'Loop 9' }),
        site(4, {}),
      ]),
    ).toEqual(['Loop 9', 'Loop 10']);
  });

  test('offers each distinct type once as a wire value with its label, label-ordered', () => {
    expect(
      typeOptions([
        site(1, { kind: 'rv', kind_label: 'RV' }),
        site(2, { kind: 'tent', kind_label: 'Tent' }),
        site(3, { kind: 'tent', kind_label: 'Tent' }),
      ]),
    ).toEqual([
      { value: 'rv', label: 'RV' },
      { value: 'tent', label: 'Tent' },
    ]);
  });

  test('never invents a label, and never shows the wire value, for a row that carries none', () => {
    expect(typeOptions([site(1, { kind: 'rv' })])).toEqual([]);
  });
});

describe('sorting', () => {
  const columns = columnsOf(day(FIRST, { 1: open, 2: open }), day(SECOND, { 1: open, 2: taken }));

  test('available-first ranks by how many days are open', () => {
    const rows = sortCampsites([site(2, { name: 'B' }), site(1, { name: 'A' })], 'available', columns);

    expect(rows.map(rowId)).toEqual(['1', '2']);
  });

  test('a tie in openings falls back to loop-then-site', () => {
    const rows = sortCampsites(
      [site(1, { name: '2', loop_name: 'Loop B' }), site(2, { name: '1', loop_name: 'Loop A' })],
      'available',
      columnsOf(day(FIRST, {})),
    );

    expect(rows.map(rowId)).toEqual(['2', '1']);
  });

  test('site order ignores the loop', () => {
    const rows = sortCampsites(
      [site(1, { name: '9', loop_name: 'Loop Z' }), site(2, { name: '10', loop_name: 'Loop A' })],
      'site',
      columns,
    );

    expect(rows.map(rowId)).toEqual(['1', '2']);
  });

  test('type order groups by kind, then loop, then site', () => {
    const rows = sortCampsites(
      [
        site(1, { name: '1', kind: 'tent', loop_name: 'Loop B' }),
        site(2, { name: '1', kind: 'rv', loop_name: 'Loop B' }),
        site(3, { name: '1', kind: 'tent', loop_name: 'Loop A' }),
      ],
      'type',
      columns,
    );

    expect(rows.map(rowId)).toEqual(['2', '3', '1']);
  });

  test('an unknown sort key is coerced to the default', () => {
    expect(normalizeFilters({ sort: 'nonsense' as never }).sort).toBe(DEFAULT_MATRIX_FILTERS.sort);
    expect(normalizeFilters(null)).toEqual(DEFAULT_MATRIX_FILTERS);
  });
});

describe("counting a row's open days", () => {
  test('counts the days whose cell for this row is available', () => {
    expect(availableDateCount(site(1), columnsOf(day(FIRST, { 1: open }), day(SECOND, { 1: taken })))).toBe(1);
  });

  test('a day with no cell for the row counts as nothing, not as the rollup', () => {
    expect(availableDateCount(site(1), columnsOf(day(FIRST, { 2: open })))).toBe(0);
  });
});

describe('what a cell means', () => {
  const columnFor = (d: AvailabilityDay) => columnsOf(d)[0];

  test('is the cell the day carries for that row', () => {
    const column = columnFor(day(FIRST, { 1: { status: 'first_come', watchable: true } }));

    expect(cellState(site(1), column)).toEqual({ status: 'first_come', watchable: true });
  });

  test('a row the day has no cell for is unknown and unwatchable', () => {
    expect(cellState(site(1), columnFor(day(FIRST, { 2: open })))).toEqual({
      status: 'unknown',
      watchable: false,
    });
  });

  test('the rollup never overrides a cell', () => {
    const column = columnFor(day(FIRST, { 1: taken }, { status: 'available' }));

    expect(cellState(site(1), column).status).toBe('reserved');
  });

  test('a night the wire never answered is unknown, not the rollup of the ones it did', () => {
    const [answered, missing] = matrixColumns([FIRST, SECOND], [day(FIRST, { 1: open })]);

    expect(cellState(site(1), answered).status).toBe('available');
    expect(cellState(site(1), missing).status).toBe('unknown');
    expect(missing.day).toBeUndefined();
  });

  test("watchable is the backend's answer, not one derived from the status", () => {
    const column = columnFor(day(FIRST, { 1: { status: 'reserved', watchable: false } }));

    expect(cellState(site(1), column).watchable).toBe(false);
  });
});
