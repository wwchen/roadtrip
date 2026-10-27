import type { AvailabilityCell, AvailabilityDay } from '@/api/availability-api';
import type { Campsite } from '@/api/campsite-api';
import { matrixCopy } from '@/lib/strings';
import { longDayLabel } from '@/lib/week-labels';

export type MatrixSort = 'site' | 'available' | 'loop' | 'type';

export interface MatrixFilters {
  query: string;
  loop: string;
  type: string;
  sort: MatrixSort;
}

export interface FilterOption {
  value: string;
  label: string;
}

export interface MatrixColumn {
  date: string;
  spoken: string;
  day: AvailabilityDay | undefined;
}

export const SORT_OPTIONS: ReadonlyArray<{ value: MatrixSort; label: string }> = [
  { value: 'site', label: matrixCopy.sortBySite },
  { value: 'available', label: matrixCopy.sortByAvailable },
  { value: 'loop', label: matrixCopy.sortByLoop },
  { value: 'type', label: matrixCopy.sortByType },
];

const AVAILABLE_FIRST: MatrixSort = 'available';

export const DEFAULT_MATRIX_FILTERS: MatrixFilters = {
  query: '',
  loop: '',
  type: '',
  sort: AVAILABLE_FIRST,
};

const NO_LOOP_SORT_KEY = '￿';

const NO_CELL: Readonly<AvailabilityCell> = Object.freeze({ status: 'unknown', watchable: false });

const COLLATE_NUMERIC: Intl.CollatorOptions = { numeric: true };

const NO_LOCALE = undefined;

export function siteName(row: Partial<Campsite>): string {
  if (row.name) return row.name;
  if (row.data_provider_ref) return matrixCopy.site(row.data_provider_ref);
  return row.id != null ? matrixCopy.site(row.id) : matrixCopy.unknownSite;
}

export function rowId(row: Partial<Campsite>): string {
  return String(row.id);
}

export function siteTitleText(row: Partial<Campsite>, label = siteName(row)): string {
  const loop = row.loop_name?.trim();
  return loop ? matrixCopy.loopAndSite(loop, label) : label;
}

export function matrixColumns(
  dates: readonly string[],
  days: readonly AvailabilityDay[],
): readonly MatrixColumn[] {
  const byDate = new Map(days.filter((day) => day?.date).map((day) => [day.date, day]));
  return dates.map((date) => ({ date, spoken: longDayLabel(date), day: byDate.get(date) }));
}

export function cellState(row: Partial<Campsite>, column: MatrixColumn): Readonly<AvailabilityCell> {
  return column.day?.cells?.[rowId(row)] ?? NO_CELL;
}

export function sortedCampsites(
  campsites: readonly Partial<Campsite>[] | null | undefined,
  columns: readonly MatrixColumn[] = [],
): Partial<Campsite>[] {
  const catalogRows = Array.isArray(campsites) ? campsites : [];
  const rows = catalogRows.length > 0 ? catalogRows : fallbackCampsitesFromColumns(columns);
  return [...rows].sort(compareCampsite);
}

function fallbackCampsitesFromColumns(columns: readonly MatrixColumn[]): Partial<Campsite>[] {
  const ids = new Set<string>();
  for (const column of columns) {
    for (const id of Object.keys(column.day?.cells ?? {})) ids.add(id);
  }
  return [...ids].sort().map((id) => ({ id: Number(id), data_provider_ref: id }));
}

export function normalizeFilters(filters: Partial<MatrixFilters> | null | undefined): MatrixFilters {
  const sort = SORT_OPTIONS.some((option) => option.value === filters?.sort)
    ? (filters?.sort as MatrixSort)
    : DEFAULT_MATRIX_FILTERS.sort;
  return {
    query: typeof filters?.query === 'string' ? filters.query : '',
    loop: typeof filters?.loop === 'string' ? filters.loop : '',
    type: typeof filters?.type === 'string' ? filters.type : '',
    sort,
  };
}

export function filterCampsites(
  rows: readonly Partial<Campsite>[],
  filters: MatrixFilters,
): Partial<Campsite>[] {
  const query = filters.query.trim().toLowerCase();
  return rows.filter((row) => {
    if (filters.loop && row.loop_name !== filters.loop) return false;
    if (filters.type && row.kind !== filters.type) return false;
    if (!query) return true;
    const haystack = [
      siteTitleText(row),
      siteName(row),
      row.loop_name,
      row.kind,
      row.kind_listed,
      row.kind_label,
      row.data_provider,
      row.data_provider_ref,
      row.id,
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return haystack.includes(query);
  });
}

export function sortCampsites(
  rows: readonly Partial<Campsite>[],
  sortKey: MatrixSort,
  columns: readonly MatrixColumn[],
): Partial<Campsite>[] {
  return [...rows].sort((a, b) => {
    if (sortKey === 'available') {
      const countA = availableDateCount(a, columns);
      const countB = availableDateCount(b, columns);
      if (countA !== countB) return countB - countA;
      return compareCampsite(a, b);
    }
    if (sortKey === 'site') return compareBySite(a, b);
    if (sortKey === 'type') return compareByType(a, b);
    return compareCampsite(a, b);
  });
}

const AVAILABLE_STATUS = 'available';

export function availableDateCount(
  row: Partial<Campsite>,
  columns: readonly MatrixColumn[],
): number {
  return columns.filter((column) => cellState(row, column).status === AVAILABLE_STATUS).length;
}

export function loopOptions(rows: readonly Partial<Campsite>[]): string[] {
  const values = rows
    .map((row) => row.loop_name)
    .filter((value): value is string => !!value?.trim());
  return [...new Set(values)].sort((a, b) => a.localeCompare(b, NO_LOCALE, COLLATE_NUMERIC));
}

export function typeOptions(rows: readonly Partial<Campsite>[]): FilterOption[] {
  const byValue = new Map<string, FilterOption>();
  for (const row of rows) {
    const value = row.kind?.trim();
    const label = row.kind_label?.trim();
    if (!value || !label || byValue.has(value)) continue;
    byValue.set(value, { value, label });
  }
  return [...byValue.values()].sort((a, b) =>
    a.label.localeCompare(b.label, NO_LOCALE, COLLATE_NUMERIC),
  );
}

function compareCampsite(a: Partial<Campsite>, b: Partial<Campsite>): number {
  const loopA = a.loop_name || NO_LOOP_SORT_KEY;
  const loopB = b.loop_name || NO_LOOP_SORT_KEY;
  if (loopA !== loopB) return loopA.localeCompare(loopB);
  return compareBySite(a, b);
}

function compareBySite(a: Partial<Campsite>, b: Partial<Campsite>): number {
  const nameA = a.name || a.data_provider_ref || '';
  const nameB = b.name || b.data_provider_ref || '';
  return nameA.localeCompare(nameB, NO_LOCALE, COLLATE_NUMERIC);
}

function compareByType(a: Partial<Campsite>, b: Partial<Campsite>): number {
  const typeA = a.kind || NO_LOOP_SORT_KEY;
  const typeB = b.kind || NO_LOOP_SORT_KEY;
  if (typeA !== typeB) return typeA.localeCompare(typeB, NO_LOCALE, COLLATE_NUMERIC);
  return compareCampsite(a, b);
}
