// Which page a POI gets, by category.
//
// A `Map`, not an object literal, because the key arrives from the network — a
// plain-object lookup would resolve `Object.prototype` members (the bug documented
// in `lib/settings-errors.ts`). Adding a type should be a row here, not a branch
// somewhere else.
import type { PoiTypeComponent } from './common';
import { CampgroundPoiPage } from './campground';
import { ChargerPoiPage } from './charger';
import { ParkPoiPage } from './park';
import { PlacePoiPage, type PlaceTypeSpec } from './place';

/**
 * The types whose page is identity, actions and one spec list.
 *
 * The field keys are the contract with the ETL: a type shows the fields it has and
 * omits the rest, so a category whose provider ships none of them still renders a
 * page — identity, one action, provenance — rather than an error.
 */
const PLACE_TYPES: Record<string, PlaceTypeSpec> = {
  gym: {
    eyebrow: 'Gym · Planet Fitness',
    fallbackName: 'Planet Fitness',
    kind: 'PF',
    heading: 'This location',
    fields: [
      { label: 'Hours', key: 'opening_hours' },
      { label: 'Phone', key: 'phone' },
    ],
    websiteLabel: 'Planet Fitness page',
    fallbackSearch: (p) =>
      `https://www.planetfitness.com/gyms?q=${encodeURIComponent(
        [p.city, p.state].filter((part) => typeof part === 'string' && part).join(' '),
      )}`,
    call: true,
  },
  trailhead: {
    eyebrow: 'Trailhead',
    fallbackName: 'Trailhead',
    kind: 'TH',
    heading: 'The hike',
    fields: [
      { label: 'Distance', key: 'trail_distance' },
      { label: 'Gain', key: 'trail_gain' },
      { label: 'Parking', key: 'trail_parking' },
      { label: 'Permit', key: 'trail_permit' },
    ],
    websiteLabel: 'Trail page',
  },
  town: {
    eyebrow: 'Town stop',
    fallbackName: 'Town',
    kind: 'TOWN',
    heading: 'On the way',
    fields: [
      { label: 'Fuel', key: 'fuel' },
      { label: 'Groceries', key: 'groceries' },
      { label: 'Cell', key: 'cell_service' },
      { label: 'Last stop', key: 'last_stop' },
    ],
  },
  pin: {
    eyebrow: 'Dropped pin',
    fallbackName: 'Dropped pin',
    kind: 'PLACE',
    heading: 'What we know',
    fields: [
      { label: 'Inside', key: 'inside_region' },
      { label: 'Nearest', key: 'nearest_place' },
      { label: 'Elevation', key: 'elevation' },
    ],
  },
  state: {
    eyebrow: 'State',
    fallbackName: 'State',
    kind: 'PLACE',
    heading: 'Camping here',
    fields: [
      { label: 'Units', key: 'unit_count' },
      { label: 'With camping', key: 'camping_unit_count' },
      { label: 'Agencies', key: 'agencies' },
    ],
  },
};

const place = (key: keyof typeof PLACE_TYPES): PoiTypeComponent => {
  const spec = PLACE_TYPES[key];
  const Page: PoiTypeComponent = (props) => PlacePoiPage({ ...props, spec });
  return Object.assign(Page, { displayName: `PlacePoiPage(${key})` });
};

/**
 * Both the canonical category and the alias reach the client depending on which
 * endpoint answered, exactly as in the overlay registry.
 */
const BY_CATEGORY = new Map<string, PoiTypeComponent>([
  ['campground', CampgroundPoiPage],
  ['national-park', ParkPoiPage],
  ['state-park', ParkPoiPage],
  ['planet_fitness_location', place('gym')],
  ['planet-fitness', place('gym')],
  ['tesla_supercharger', ChargerPoiPage],
  ['supercharger', ChargerPoiPage],
  ['trailhead', place('trailhead')],
  ['town', place('town')],
  ['dropped-pin', place('pin')],
  ['state', place('state')],
]);

/**
 * The page component for a hydrated POI, or null when nothing renders it.
 *
 * Takes the flattened properties rather than a category string, because for a
 * campground the category is not what came off the wire: `flattenHydratedPoi`
 * rewrites `category` to the campground's `subcategory` (a core.js behaviour its
 * parity suite pins), so `'federal'`, `'state'` and `'provincial'` arrive here where
 * `'campground'` went in — and a campground with a subcategory is the common case,
 * not the exception. Hence the second lookup: a POI whose category IS its
 * subcategory has been through that rewrite, and only campgrounds are rewritten.
 *
 * The rewrite is checked BEFORE the table, not after, because the two namespaces
 * genuinely collide: a state-managed campground arrives as `category: 'state'`, and
 * so does a state page. Only a campground can have `category === subcategory`, so
 * that test settles it without either entry having to be renamed around the other.
 *
 * Null rather than a fallback page: a category with no renderer is a gap in this
 * table, and the dropped-pin page — which IS the "almost everything absent" page —
 * would hide that gap behind something that looks deliberate.
 */
export function poiPageFor(
  properties: Readonly<Record<string, unknown>> | null | undefined,
): PoiTypeComponent | null {
  const category = properties?.category;
  if (typeof category !== 'string') return null;
  if (category === properties?.subcategory) return CampgroundPoiPage;
  return BY_CATEGORY.get(category) ?? null;
}
