// The campground filter's agency logic.
//
// Agency is the one classification every campground source populates, so the
// filter narrows by it. The pick is SHOW-ONLY: empty means every agency, and a
// non-empty pick keeps only the agencies in it, wherever the user pans.
import type { ExpressionSpecification, FilterSpecification } from 'maplibre-gl';
import { pinFeatureId, type PinFeature } from './pins';

/** The row campgrounds with no agency are counted under. */
export const UNCATEGORIZED_AGENCY = 'Uncategorized';

const CAMPGROUND_CATEGORY = 'campground';

/** Shared empty set, so "nothing excluded" is one stable identity for memo consumers. */
const NO_EXCLUDED_IDS: ReadonlySet<number> = new Set();

/**
 * An agency name, or the sentinel when the pin carries none.
 *
 * Typed on `properties` rather than on `PinFeature`: the filter passes viewport
 * pins, but the shared-link restore passes a hydrated `/api/pois/{id}` feature,
 * and this reads no geometry — requiring the full pin shape would make callers
 * cast to satisfy a field the function never touches.
 */
export function featureAgency(
  feature: { properties?: { agency?: unknown } | null } | null | undefined,
): string {
  return agencyName(feature?.properties?.agency);
}

export function agencyName(agency: unknown): string {
  return (typeof agency === 'string' ? agency.trim() : '') || UNCATEGORIZED_AGENCY;
}

/**
 * Campgrounds per agency in the given pins.
 *
 * Non-campground features are skipped rather than trusted to be absent: the
 * caller passes the campground bucket today, but the on-route payload mixes
 * categories and this is the count the agency filter shows.
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

export function excludedAgencyPinIds(
  features: readonly PinFeature[],
  picked: readonly string[],
): ReadonlySet<number> {
  if (picked.length === 0) return NO_EXCLUDED_IDS;
  const keep = new Set(picked);
  const ids = new Set<number>();
  for (const feature of features) {
    if (feature?.properties?.category !== CAMPGROUND_CATEGORY) continue;
    if (keep.has(featureAgency(feature))) continue;
    const id = pinFeatureId(feature);
    if (typeof id === 'number') ids.add(id);
  }
  return ids;
}

/**
 * The ids left once the excluded agencies are dropped.
 *
 * This has to run BEFORE a caller caps the list, or a view an unpicked agency
 * dominates fills the cap with rows the caller then drops and shows a handful of
 * results. An id with no pin in the set stays: the pin loop samples above its
 * budget and lags a pan by one fetch, and "no agency known" is not the same
 * answer as "an agency you did not pick". The same array comes back when nothing
 * is excluded, so a memo downstream does not churn.
 */
export function idsWithVisibleAgency(
  ids: readonly number[],
  excludedIds: ReadonlySet<number>,
): readonly number[] {
  if (excludedIds.size === 0) return ids;
  return ids.filter((id) => !excludedIds.has(id));
}

export interface AgencyCount {
  agency: string;
  count: number;
}

export function agenciesByCount(
  counts: ReadonlyMap<string, number>,
  picked: readonly string[],
): AgencyCount[] {
  const inView = [...counts.entries()]
    .map(([agency, count]) => ({ agency, count }))
    .sort((a, b) => b.count - a.count || a.agency.localeCompare(b.agency));
  const gone = picked.filter((agency) => !counts.has(agency)).map((agency) => ({ agency, count: 0 }));
  return [...inView, ...gone];
}

function pickedAgencyClause(picked: readonly string[]): ExpressionSpecification | null {
  if (picked.length === 0) return null;
  const named = picked.filter((agency) => agency !== UNCATEGORIZED_AGENCY);
  const alternatives: ExpressionSpecification[] = [];
  if (named.length > 0) alternatives.push(['in', ['get', 'agency'], ['literal', named]]);
  if (picked.includes(UNCATEGORIZED_AGENCY)) alternatives.push(['!', ['has', 'agency']]);
  return ['any', ...alternatives];
}

/**
 * The campground layer filter: the agency pick combined with the active pin
 * filter, if any.
 *
 * Null means "no filter" — MapLibre wants the filter removed rather than set to a
 * tautology. `matchingIds` null means no catalog filter is active; non-null, it
 * also requires the feature's top-level `id` — the one MapLibre reads through
 * `['id']` — to be in the set.
 */
export function campgroundLayerFilter(
  picked: readonly string[],
  matchingIds: ReadonlySet<number> | null,
): FilterSpecification | null {
  const clauses: ExpressionSpecification[] = [];
  const agencyClause = pickedAgencyClause(picked);
  if (agencyClause) clauses.push(agencyClause);
  if (matchingIds !== null) clauses.push(['in', ['id'], ['literal', [...matchingIds]]]);
  return clauses.length === 0 ? null : ['all', ...clauses];
}
