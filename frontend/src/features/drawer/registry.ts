// Which drawer a POI gets, by category.
//
// Same shape as `map/overlays.ts`'s registry: adding a
// category should be a row, not another branch. A `Map`, not an object literal,
// because the key arrives from the network — a plain-object lookup would resolve
// `Object.prototype` members (the bug documented in `lib/settings-errors.ts`).
import type { ComponentType } from 'react';
import type { FlatPoiFeature } from '@/lib/poi';
import { CampgroundDrawer } from './CampgroundDrawer';
import { ParkDrawer } from './ParkDrawer';
import { PlanetFitnessDrawer } from './PlanetFitnessDrawer';
import { SuperchargerDrawer } from './SuperchargerDrawer';

export interface DrawerContentProps {
  feature: FlatPoiFeature;
  /** Close the drawer — the "add stop" buttons dismiss after acting, as before. */
  onClose: () => void;
}

export type DrawerContent = ComponentType<DrawerContentProps>;

export type DrawerRegistration =
  | { kind: 'campground'; Content: typeof CampgroundDrawer }
  | { kind: 'standard'; Content: DrawerContent };

const campgroundRegistration: DrawerRegistration = {
  kind: 'campground',
  Content: CampgroundDrawer,
};
const standardRegistration = (Content: DrawerContent): DrawerRegistration => ({
  kind: 'standard',
  Content,
});

/**
 * Both the canonical category and the alias reach the client depending on which
 * endpoint answered, exactly as in the overlay registry.
 */
const BY_CATEGORY = new Map<string, DrawerRegistration>([
  ['campground', campgroundRegistration],
  ['national-park', standardRegistration(ParkDrawer)],
  ['state-park', standardRegistration(ParkDrawer)],
  ['planet_fitness_location', standardRegistration(PlanetFitnessDrawer)],
  ['planet-fitness', standardRegistration(PlanetFitnessDrawer)],
  ['tesla_supercharger', standardRegistration(SuperchargerDrawer)],
  ['supercharger', standardRegistration(SuperchargerDrawer)],
  // Complete for every category the map paints. A category with no entry still opens
  // the drawer and says so — see `PoiDrawer` — rather than swallowing the click.
]);

/**
 * The drawer registration for a hydrated POI, or null when nothing renders it.
 *
 * Takes the flattened properties rather than a category string, because for a
 * campground the category is not what came off the wire: `flattenHydratedPoi`
 * rewrites `category` to the campground's `subcategory` (a core.js behaviour its
 * parity suite pins), so `'federal'`, `'state'` and `'provincial'` arrive here where
 * `'campground'` went in — and a campground with a subcategory is the common case,
 * not the exception. Hence the second lookup: a POI whose category IS its
 * subcategory has been through that rewrite, and only campgrounds are rewritten.
 *
 * The vanilla never had to answer this question. It dispatched by which layer was
 * clicked (`layers.js` called `openCampgroundDrawer` directly), so the rewritten
 * category was only ever read for display.
 *
 * Null rather than a fallback drawer: a category with no renderer is a gap in this
 * table, and an "unknown POI" panel would hide it behind something that looks
 * deliberate.
 */
export function drawerFor(
  properties: Readonly<Record<string, unknown>> | null | undefined,
): DrawerRegistration | null {
  const category = properties?.category;
  if (typeof category !== 'string') return null;
  const direct = BY_CATEGORY.get(category);
  if (direct) return direct;
  return category === properties?.subcategory ? campgroundRegistration : null;
}
