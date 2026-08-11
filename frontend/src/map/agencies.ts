// The campground legend's agency logic.
//
// Campgrounds are the one overlay with no on/off switch: there are 50+ managing
// agencies and the legend filters by them instead.
//
// The legend is VIEWPORT-SCOPED: rows come from the campgrounds currently in
// view, with no accumulated "agencies I have ever seen" set, so panning away
// from a region drops its agencies. The user's choices are stored the other way
// round — as the set of agencies explicitly switched OFF (see
// `stores/mapStore`), so an agency nobody has seen before defaults to visible
// and panning into a new region shows everything there.
import type { ExpressionSpecification, FilterSpecification } from 'maplibre-gl';
import type { PinFeature } from './pins';

/** The row campgrounds with no agency are counted under. */
export const UNCATEGORIZED_AGENCY = 'Uncategorized';

const CAMPGROUND_CATEGORY = 'campground';

/**
 * An agency name, or the sentinel when the pin carries none.
 *
 * Typed on `properties` rather than on `PinFeature`: the legend passes viewport
 * pins, but the shared-link restore passes a hydrated `/api/pois/{id}` feature,
 * and this reads no geometry — requiring the full pin shape would make callers
 * cast to satisfy a field the function never touches.
 */
export function featureAgency(
  feature: { properties?: { agency?: unknown } | null } | null | undefined,
): string {
  const agency = feature?.properties?.agency;
  return (typeof agency === 'string' ? agency.trim() : '') || UNCATEGORIZED_AGENCY;
}

/**
 * Campgrounds per agency in the given pins.
 *
 * Non-campground features are skipped rather than trusted to be absent: the
 * caller passes the campground bucket today, but the on-route payload mixes
 * categories and this is the count the legend shows.
 */
export function agencyCounts(features: readonly PinFeature[]): Map<string, number> {
  const counts = new Map<string, number>();
  for (const feature of features) {
    if (feature?.properties?.category !== CAMPGROUND_CATEGORY) continue;
    const agency = featureAgency(feature);
    counts.set(agency, (counts.get(agency) ?? 0) + 1);
  }
  return counts;
}

/** Legend row order: the agency names, alphabetically. */
export function sortedAgencies(counts: ReadonlyMap<string, number>): string[] {
  return [...counts.keys()].sort((a, b) => a.localeCompare(b));
}

/**
 * The campground layer filter for a set of switched-off agencies.
 *
 * Null means "no filter" — MapLibre wants the filter removed rather than set to a
 * tautology.
 *
 * The `Uncategorized` row needs its own clause because it is a sentinel, not a
 * value: MapLibre can only test properties that are present, so hiding it means
 * excluding the features with no `agency` at all. One known seam, carried over
 * from the original rather than silently changed: a pin whose agency is
 * whitespace counts as `Uncategorized` in the legend (`featureAgency` trims) but
 * still satisfies `['has', 'agency']`, so hiding that row leaves it painted.
 */
export function hiddenAgencyFilter(hidden: readonly string[]): FilterSpecification | null {
  const hideUncategorized = hidden.includes(UNCATEGORIZED_AGENCY);
  const namedHidden = hidden.filter((agency) => agency !== UNCATEGORIZED_AGENCY);
  if (namedHidden.length === 0 && !hideUncategorized) return null;

  const clauses: ExpressionSpecification[] = [];
  if (namedHidden.length > 0) {
    clauses.push(['!', ['in', ['get', 'agency'], ['literal', namedHidden]]]);
  }
  if (hideUncategorized) {
    clauses.push(['has', 'agency']);
  }
  return ['all', ...clauses];
}
