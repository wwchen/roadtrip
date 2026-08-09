// Which drawer a POI gets, by category.
//
// The vanilla map had no dispatch at all: each layer's click handler called its own
// `openXDrawer` directly, so "what opens for this POI" was spread across five
// modules and only worked for POIs that came from a painted layer. A POI reached by
// deep link (`?poi=<id>`) has a category and nothing else, which is exactly what a
// table keys off.
//
// Same shape as `map/overlays.ts`'s registry, and for the same reason: adding a
// category should be a row, not another branch. A `Map`, not an object literal,
// because the key arrives from the network — a plain-object lookup would resolve
// `Object.prototype` members (the bug documented in `lib/settings-errors.ts`).
import type { ComponentType } from 'react';
import type { FlatPoiFeature } from '@/lib/poi';
import { ParkDrawer } from './ParkDrawer';
import { PlanetFitnessDrawer } from './PlanetFitnessDrawer';

export interface DrawerContentProps {
  feature: FlatPoiFeature;
  /** Close the drawer — the "add stop" buttons dismiss after acting, as before. */
  onClose: () => void;
}

export type DrawerContent = ComponentType<DrawerContentProps>;

/**
 * Both the canonical category and the alias reach the client depending on which
 * endpoint answered, exactly as in the overlay registry.
 */
const BY_CATEGORY = new Map<string, DrawerContent>([
  ['national-park', ParkDrawer],
  ['state-park', ParkDrawer],
  ['planet_fitness_location', PlanetFitnessDrawer],
  ['planet-fitness', PlanetFitnessDrawer],
  // Still to come in 4c: `tesla_supercharger`/`supercharger` (a 288-line port) and
  // `campground` (275 lines plus the 589-line detail card, whose availability
  // section is the 4d boundary). Until they land, those pins open the drawer and
  // say so — `PoiDrawer`'s "no detail view yet" banner — which is a visible,
  // truthful gap rather than a click that appears to do nothing.
]);

/**
 * The drawer for a category, or null when nothing renders it.
 *
 * Null rather than a fallback drawer: a category with no renderer is a gap in this
 * table, and an "unknown POI" panel would hide it behind something that looks
 * deliberate.
 */
export function drawerFor(category: unknown): DrawerContent | null {
  return typeof category === 'string' ? BY_CATEGORY.get(category) ?? null : null;
}
