// Which rows the matrix shows, in what order, and what each cell means.
//
// The logic half of web/availability/site-matrix.js, extracted from the rendering
// half. Everything here is pure: given a campsite catalog, the fused days and the
// filter state, it answers "which rows, in which order" and "what is this cell".
// The component below it only draws.
//
// Worth separating rather than leaving inline, because these are the rules a
// reviewer needs to check independently of any markup — particularly `cellState`,
// which decides whether someone is shown a booking button.
import { availabilityStatusMeta, normalizeAvailabilityStatus } from '@/lib/availability-status';
import type { AvailabilityStatusMeta } from '@/lib/availability-status';
import type { Campsite } from '@/api/campsite-api';
import type { FusedDay } from './fuse';

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

/**
 * Cell statuses a watch can be set on.
 *
 * Occupied now, but able to open up. Excludes `available` (already bookable, so the
 * cell is a booking button instead), `closed` and `unknown` (nothing to wait for),
 * and `past`. Hyphenated because these are `AvailabilityStatusMeta.kind` values,
 * not wire values — `first_come` renders as `first-come`.
 */
const WATCHABLE_KINDS: ReadonlySet<string> = new Set(['reserved', 'first-come']);

/** Sorts loop-less rows last: they are Aspira's resource-id-only rows. */
const NO_LOOP_SORT_KEY = '￿';

export function isWatchableKind(kind: string): boolean {
  return WATCHABLE_KINDS.has(kind);
}

/** A campsite's display name, however little the provider gave us. */
export function siteName(row: Partial<Campsite>): string {
  if (row.name) return String(row.name);
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
  const loop = typeof row.loop_name === 'string' ? row.loop_name.trim() : '';
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
  days: readonly FusedDay[] = [],
): Partial<Campsite>[] {
  const catalogRows = Array.isArray(campsites) ? campsites : [];
  const rows = catalogRows.length > 0 ? catalogRows : fallbackCampsitesFromDays(days);
  return [...rows].sort(compareCampsite);
}

function fallbackCampsitesFromDays(days: readonly FusedDay[]): Partial<Campsite>[] {
  const ids = new Set<string>();
  for (const day of Array.isArray(days) ? days : []) {
    for (const id of Object.keys(day?.campsite_statuses ?? {})) ids.add(String(id));
    for (const id of day?.available_campsite_ids ?? []) ids.add(String(id));
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

export interface SortContext {
  /** `date → set of bookable campsite ids`, as strings. */
  availabilityByDate: Map<string, Set<string>>;
  visibleDays: readonly FusedDay[];
}

/** Rows in the chosen order. Every sort falls back to loop-then-site for stability. */
export function sortCampsites(
  rows: readonly Partial<Campsite>[],
  sortKey: MatrixSort,
  context: SortContext,
): Partial<Campsite>[] {
  return [...rows].sort((a, b) => {
    if (sortKey === 'available') {
      const countA = availableDateCount(a, context);
      const countB = availableDateCount(b, context);
      if (countA !== countB) return countB - countA;
      return compareCampsite(a, b);
    }
    if (sortKey === 'site') return compareBySite(a, b);
    if (sortKey === 'type') return compareByType(a, b);
    return compareCampsite(a, b);
  });
}

/** How many of the visible days this site is bookable on. */
export function availableDateCount(row: Partial<Campsite>, context: SortContext): number {
  const campsiteId = rowId(row);
  let count = 0;
  for (const day of context.visibleDays) {
    const status = campsiteStatus(day, campsiteId);
    if (status === 'available') count += 1;
    // Only when the day carried no explicit status for this site: the derived
    // id list must not override a stream that said "reserved".
    else if (!status && context.availabilityByDate.get(day.date)?.has(campsiteId)) count += 1;
  }
  return count;
}

/** The distinct values of a column, for a filter dropdown. */
export function filterOptions(rows: readonly Partial<Campsite>[], key: keyof Campsite): string[] {
  return [
    ...new Set(
      rows
        .map((row) => row[key])
        .filter((value): value is string => typeof value === 'string' && value.trim() !== ''),
    ),
  ].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
}

/**
 * That site's status on that day.
 *
 * Precedence, and it matters: an explicit per-campsite status wins; then the day's
 * derived id list; and only then the day's rolled-up status. The last branch is the
 * subtle one — a day that rolled up to `available` says nothing about *this* site,
 * so when the day also carried an id list (i.e. we know which sites are open, and
 * this is not one of them) the honest answer is `reserved`, not `available`.
 */
export function cellState(
  row: Partial<Campsite>,
  day: FusedDay,
  availableIds: Set<string> | undefined,
): Readonly<AvailabilityStatusMeta> {
  const campsiteId = rowId(row);
  const direct = campsiteStatus(day, campsiteId);
  if (direct) return availabilityStatusMeta(direct);
  if (availableIds?.has(campsiteId)) return availabilityStatusMeta('available');

  const status = normalizeAvailabilityStatus(day.status);
  if (status === 'available' && availableIds) return availabilityStatusMeta('reserved');
  return availabilityStatusMeta(status);
}

/**
 * A day's explicit status for one campsite, or null when it carried none.
 *
 * `hasOwnProperty` rather than a truthiness check, because "absent" and "present
 * but unreadable" are different: an absent id falls through to the derived list,
 * where a present-but-junk one must resolve to `unknown` and stop there.
 */
function campsiteStatus(day: FusedDay | null | undefined, campsiteId: string): string | null {
  const statuses = day?.campsite_statuses;
  if (!statuses || typeof statuses !== 'object') return null;
  if (!Object.prototype.hasOwnProperty.call(statuses, campsiteId)) return null;
  return normalizeAvailabilityStatus(statuses[campsiteId]);
}

/** `date → bookable campsite ids`, the index the sorts and cells share. */
export function availabilityIndex(days: readonly FusedDay[]): Map<string, Set<string>> {
  return new Map(
    days.map((day) => [day.date, new Set((day.available_campsite_ids ?? []).map(String))]),
  );
}

function compareCampsite(a: Partial<Campsite>, b: Partial<Campsite>): number {
  const loopA = a.loop_name || NO_LOOP_SORT_KEY;
  const loopB = b.loop_name || NO_LOOP_SORT_KEY;
  if (loopA !== loopB) return String(loopA).localeCompare(String(loopB));
  return compareBySite(a, b);
}

function compareBySite(a: Partial<Campsite>, b: Partial<Campsite>): number {
  const nameA = a.name || a.data_provider_ref || '';
  const nameB = b.name || b.data_provider_ref || '';
  // Numeric collation, so "Site 9" precedes "Site 10".
  return String(nameA).localeCompare(String(nameB), undefined, { numeric: true });
}

function compareByType(a: Partial<Campsite>, b: Partial<Campsite>): number {
  const typeA = a.kind || NO_LOOP_SORT_KEY;
  const typeB = b.kind || NO_LOOP_SORT_KEY;
  if (typeA !== typeB) {
    return String(typeA).localeCompare(String(typeB), undefined, { numeric: true });
  }
  return compareCampsite(a, b);
}
