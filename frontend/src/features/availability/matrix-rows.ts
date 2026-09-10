// Which rows the matrix shows and in what order.
//
// Given a campsite catalog, the week the API served and the filter state, it
// answers "which rows, in which order". What a cell means is not decided here:
// the response carries one cell per campsite per day.
import type { Campsite } from '@/api/campsite-api';
import type { AvailabilityCell, AvailabilityDay } from '@/api/availability-api';

export type MatrixSort = 'site' | 'available' | 'loop' | 'type';

export interface MatrixFilters {
  query: string;
  loop: string;
  type: string;
  sort: MatrixSort;
}

/** Sort options, in the order the picker lists them. */
export const SORT_OPTIONS: ReadonlyArray<{ value: MatrixSort; label: string }> = [
  { value: 'site', label: 'Site' },
  { value: 'available', label: 'Available first' },
  { value: 'loop', label: 'Loop' },
  { value: 'type', label: 'Type' },
];

export const DEFAULT_MATRIX_FILTERS: MatrixFilters = {
  query: '',
  loop: '',
  type: '',
  // Available-first by default: the reason anyone opens this grid is to find an
  // opening, and burying them under 200 reserved rows makes the grid useless.
  sort: 'available',
};

/** Sorts loop-less rows last: they are Aspira's resource-id-only rows. */
const NO_LOOP_SORT_KEY = '￿';

/** What a row with no cell for a day reads as: nothing known, nothing to watch. */
const NO_CELL: Readonly<AvailabilityCell> = Object.freeze({ status: 'unknown', watchable: false });

/** A campsite's display name, however little the provider gave us. */
export function siteName(row: Partial<Campsite>): string {
  if (row.name) return row.name;
  // Aspira's availability map ships resource ids and no names, so a numbered
  // fallback beats "(unnamed)" — the number is what is printed on the post.
  if (row.data_provider_ref) return `Site #${row.data_provider_ref}`;
  return row.id != null ? `Site #${row.id}` : '(unknown)';
}

export function rowId(row: Partial<Campsite>): string {
  return String(row.id);
}

/** "Loop A / Site 12", or just the site when the provider has no loops. */
export function siteTitleText(row: Partial<Campsite>, label = siteName(row)): string {
  const loop = row.loop_name?.trim();
  return loop ? `${loop} / ${label}` : label;
}

/**
 * The catalog rows to draw, loop-then-site ordered.
 *
 * Falls back to synthesising rows from the days when the catalog is empty, which
 * is not defensive padding: the availability endpoint and the catalog endpoint are
 * separate calls, and the grid should still draw if the second one is slower or
 * fails. A synthesised row has an id and nothing else.
 */
export function sortedCampsites(
  campsites: readonly Partial<Campsite>[] | null | undefined,
  days: readonly AvailabilityDay[] = [],
): Partial<Campsite>[] {
  const catalogRows = Array.isArray(campsites) ? campsites : [];
  const rows = catalogRows.length > 0 ? catalogRows : fallbackCampsitesFromDays(days);
  return [...rows].sort(compareCampsite);
}

function fallbackCampsitesFromDays(days: readonly AvailabilityDay[]): Partial<Campsite>[] {
  const ids = new Set<string>();
  for (const day of Array.isArray(days) ? days : []) {
    for (const id of Object.keys(day?.cells ?? {})) ids.add(id);
  }
  return [...ids].sort().map((id) => ({ id: id as unknown as number, data_provider_ref: id }));
}

/** Coerce whatever is in state into a complete, valid filter set. */
export function normalizeFilters(filters: Partial<MatrixFilters> | null | undefined): MatrixFilters {
  const sort = SORT_OPTIONS.some((option) => option.value === filters?.sort)
    ? (filters!.sort as MatrixSort)
    : DEFAULT_MATRIX_FILTERS.sort;
  return {
    query: typeof filters?.query === 'string' ? filters.query : '',
    loop: typeof filters?.loop === 'string' ? filters.loop : '',
    type: typeof filters?.type === 'string' ? filters.type : '',
    sort,
  };
}

/**
 * Rows matching the filters.
 *
 * The free-text query searches everything identifying about a row — name, loop,
 * both type fields, the vendor and its id — because a user typing "43" may mean
 * the site number, the provider ref, or the loop.
 */
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

/** Rows in the chosen order. Every sort falls back to loop-then-site for stability. */
export function sortCampsites(
  rows: readonly Partial<Campsite>[],
  sortKey: MatrixSort,
  visibleDays: readonly AvailabilityDay[],
): Partial<Campsite>[] {
  return [...rows].sort((a, b) => {
    if (sortKey === 'available') {
      const countA = availableDateCount(a, visibleDays);
      const countB = availableDateCount(b, visibleDays);
      if (countA !== countB) return countB - countA;
      return compareCampsite(a, b);
    }
    if (sortKey === 'site') return compareBySite(a, b);
    if (sortKey === 'type') return compareByType(a, b);
    return compareCampsite(a, b);
  });
}

/** How many of the visible days this site is bookable on. */
export function availableDateCount(
  row: Partial<Campsite>,
  visibleDays: readonly AvailabilityDay[],
): number {
  return visibleDays.filter((day) => cellState(row, day).status === 'available').length;
}

/** The distinct loops present, for the loop dropdown. */
export function loopOptions(rows: readonly Partial<Campsite>[]): string[] {
  const values = rows.map((row) => row.loop_name).filter((value): value is string => !!value?.trim());
  return [...new Set(values)].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

/**
 * The distinct site types present, for the type dropdown: the filter runs on the
 * wire value, the option reads as the backend's label. Every catalog row carries
 * both; a row synthesised from the days carries neither.
 */
export function typeOptions(rows: readonly Partial<Campsite>[]): FilterOption[] {
  const byValue = new Map<string, FilterOption>();
  for (const row of rows) {
    const value = row.kind?.trim();
    const label = row.kind_label?.trim();
    if (!value || !label || byValue.has(value)) continue;
    byValue.set(value, { value, label });
  }
  return [...byValue.values()].sort((a, b) => a.label.localeCompare(b.label, undefined, { numeric: true }));
}

export interface FilterOption {
  value: string;
  label: string;
}

/**
 * That site's cell on that day.
 *
 * A straight lookup, because the backend already folded the streams together and
 * answered both halves — the status and whether a watch could be set on it. A day
 * that carries no cell for this row is a row the campground does not have that
 * day, which reads as `unknown` rather than being inferred from the rollup.
 */
export function cellState(
  row: Partial<Campsite>,
  day: AvailabilityDay,
): Readonly<AvailabilityCell> {
  return day?.cells?.[rowId(row)] ?? NO_CELL;
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
  // Numeric collation, so "Site 9" precedes "Site 10".
  return nameA.localeCompare(nameB, undefined, { numeric: true });
}

function compareByType(a: Partial<Campsite>, b: Partial<Campsite>): number {
  const typeA = a.kind || NO_LOOP_SORT_KEY;
  const typeB = b.kind || NO_LOOP_SORT_KEY;
  if (typeA !== typeB) {
    return typeA.localeCompare(typeB, undefined, { numeric: true });
  }
  return compareCampsite(a, b);
}
