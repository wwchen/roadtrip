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
import { pinFeatureId, type PinFeature } from './pins';

/** The row campgrounds with no agency are counted under. */
export const UNCATEGORIZED_AGENCY = 'Uncategorized';

const CAMPGROUND_CATEGORY = 'campground';

/** Shared empty set, so "nothing hidden" is one stable identity for memo consumers. */
const NO_HIDDEN_IDS: ReadonlySet<number> = new Set();

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

/**
 * The POI ids of the campground pins whose agency the legend has switched off.
 *
 * The switches are a view filter, not a search parameter: the boundary search
 * answers for every agency, and the pins already carry the agency, so the
 * narrowing happens on this side — see [idsWithVisibleAgency].
 */
export function hiddenAgencyPinIds(
  features: readonly PinFeature[],
  hidden: readonly string[],
): ReadonlySet<number> {
  if (hidden.length === 0) return NO_HIDDEN_IDS;
  const hiddenAgencies = new Set(hidden);
  const ids = new Set<number>();
  for (const feature of features) {
    if (feature?.properties?.category !== CAMPGROUND_CATEGORY) continue;
    if (!hiddenAgencies.has(featureAgency(feature))) continue;
    const id = pinFeatureId(feature);
    // Numbers only, the same narrowing the map's own pin filter does: a POI id
    // that came back as a string could never match a search id, and counting it
    // would overstate how much the legend is holding back.
    if (typeof id === 'number') ids.add(id);
  }
  return ids;
}

/**
 * The ids left once the switched-off agencies are dropped.
 *
 * This has to run BEFORE a caller caps the list, or a view a switched-off agency
 * dominates fills the cap with rows the caller then drops and shows a handful of
 * results. An id with no pin in the set stays: the pin loop samples above its
 * budget and lags a pan by one fetch, and "no agency known" is not the same
 * answer as "an agency you switched off". The same array comes back when nothing
 * is hidden, so a memo downstream does not churn.
 */
export function idsWithVisibleAgency(
  ids: readonly number[],
  hiddenIds: ReadonlySet<number>,
): readonly number[] {
  if (hiddenIds.size === 0) return ids;
  return ids.filter((id) => !hiddenIds.has(id));
}

/** Legend row order: the agency names, alphabetically. */
export function sortedAgencies(counts: ReadonlyMap<string, number>): string[] {
  return [...counts.keys()].sort((a, b) => a.localeCompare(b));
}

/**
 * Filter clauses to exclude the given agencies and, if requested, uncategorized
 * campgrounds.
 *
 * The `Uncategorized` row needs its own clause because it is a sentinel, not a
 * value: MapLibre can only test properties that are present, so hiding it means
 * excluding the features with no `agency` at all. One known seam, carried over
 * from the original rather than silently changed: a pin whose agency is
 * whitespace counts as `Uncategorized` in the legend (`featureAgency` trims) but
 * still satisfies `['has', 'agency']`, so hiding that row leaves it painted.
 */
function hiddenAgencyClauses(hidden: readonly string[]): ExpressionSpecification[] {
  const hideUncategorized = hidden.includes(UNCATEGORIZED_AGENCY);
  const namedHidden = hidden.filter((agency) => agency !== UNCATEGORIZED_AGENCY);

  const clauses: ExpressionSpecification[] = [];
  if (namedHidden.length > 0) {
    clauses.push(['!', ['in', ['get', 'agency'], ['literal', namedHidden]]]);
  }
  if (hideUncategorized) {
    clauses.push(['has', 'agency']);
  }
  return clauses;
}

/**
 * The campground layer filter for a set of switched-off agencies.
 *
 * Null means "no filter" — MapLibre wants the filter removed rather than set to a
 * tautology.
 */
export function hiddenAgencyFilter(hidden: readonly string[]): FilterSpecification | null {
  const clauses = hiddenAgencyClauses(hidden);
  return clauses.length === 0 ? null : ['all', ...clauses];
}

/**
 * The campground layer filter: hidden agencies combined with the active pin
 * filter, if any.
 *
 * `matchingIds` null means no filter is active, so every pin the agency clause
 * allows stays. Non-null, it also requires the feature's top-level `id` — the
 * one MapLibre reads through `['id']` — to be in the set.
 */
export function campgroundLayerFilter(
  hidden: readonly string[],
  matchingIds: ReadonlySet<number> | null,
): FilterSpecification | null {
  const clauses = [...hiddenAgencyClauses(hidden)];

  if (matchingIds !== null) {
    clauses.push(['in', ['id'], ['literal', [...matchingIds]]]);
  }

  return clauses.length === 0 ? null : ['all', ...clauses];
}
