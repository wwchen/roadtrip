# Campgrounds In View (M1 of #565) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The topbar's results list shows the campgrounds in the current map view without a route, each card hydrated from the detail endpoint with a site count line and a "checkable online" tag.

**Architecture:** The viewport loop (`features/map/useViewportPois`) already buckets campground pins; it publishes them to `mapStore` so the topbar (`features/trip`) can read them without crossing the feature boundary. The existing placeholder-then-hydrate card pipeline (`trip-cards.ts` + `useTripCards`) gains a route-less builder, and `TripResults` gains a `viewport` variant beside its `route` one. Site counts come from one cached campsites fetch per card. No backend changes.

**Tech Stack:** React 18 + TypeScript, TanStack Query (`useQueries`), Zustand (`mapStore`), Vitest + Testing Library + jsdom, LDS via `@ui`.

**Spec:** `docs/superpowers/specs/2026-09-12-campground-availability-map-design.md` (mirrors [#565](https://github.com/wwchen/roadtrip/issues/565); this plan is milestone M1 only).

## Global Constraints

- `features/<a>` never imports from `features/<b>`; shared state goes through `@/stores`, shared logic through `@/lib` or `@/map`. `npm run lint` enforces this.
- Never edit `frontend/src/api/generated/api-types.ts`. No DTO changes in M1, so `make api-types` is not needed.
- New user-facing copy for availability wording goes in `frontend/src/lib/strings.ts` as a `...Copy` object; existing route-list copy stays where it is.
- Colours are `--rt-*` semantic roles only; JS reads them through `token()` from `@tokens`. M1 adds no CSS and no colours.
- No inline magic constants: durations and limits are named `const`s.
- Comments are short and rare (repo rule); write them for the non-obvious only.
- Tests assert on what a user sees (roles, labels, text); `data-testid` is a last resort.
- Commit messages follow `type(scope): sentence` as in `git log`, ending with the Co-Authored-By line from the session.
- All frontend commands run from `frontend/` (`npm run test -- <file>`, `npm run typecheck`, `npm run lint`).
- Branch: create `feat/565-m1-campgrounds-in-view` from `master` before Task 1 (use `superpowers:using-git-worktrees`).

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `frontend/src/stores/mapStore.ts` | modify | Hold the viewport's campground pins and the zoom-gate flag. |
| `frontend/src/stores/mapStore.test.ts` | modify | Pin the new state and setter. |
| `frontend/src/features/map/useViewportPois.ts` | modify | Publish campground pins + `campgroundsRequested` to the store. |
| `frontend/src/features/map/MapView.test.tsx` | modify | Prove the publish happens on a real viewport fetch. |
| `frontend/src/map/viewport.ts` / `viewport.test.ts` | modify | `bboxCenter` helper. |
| `frontend/src/features/trip/trip-cards.ts` / `.test.ts` | modify | Route-less card builder; `checkable`, `rating`, nullable `routeKm`. |
| `frontend/src/features/trip/site-counts.ts` / `.test.ts` | create | Count a campsite catalog. |
| `frontend/src/features/trip/useSiteCounts.ts` | create | One cached campsites fetch per card. |
| `frontend/src/lib/strings.ts` | modify | `inViewCopy`. |
| `frontend/src/features/trip/TripResults.tsx` | modify | `route` / `viewport` variants. |
| `frontend/src/features/trip/TopBar.tsx` / `TopBar.test.tsx` | modify | Wire the in-view list when there is no route. |

---

### Task 1: The store carries the viewport's campgrounds

**Files:**
- Modify: `frontend/src/stores/mapStore.ts`
- Modify: `frontend/src/features/map/useViewportPois.ts:196-212`
- Test: `frontend/src/stores/mapStore.test.ts`
- Test: `frontend/src/features/map/MapView.test.tsx`

**Interfaces:**
- Consumes: `PinFeature` from `@/map/pins`; `ViewportPois.buckets.cg.features` and `campgroundsRequested` inside `useViewportPois`.
- Produces: on `MapState`: `viewportCampgrounds: PinFeature[]`, `campgroundsRequested: boolean`, `setViewportCampgrounds(features: PinFeature[], requested: boolean): void`. Task 4 reads the first two.

- [ ] **Step 1: Write the failing store test**

Append to `frontend/src/stores/mapStore.test.ts` (add `import type { PinFeature } from '@/map/pins';` at the top):

```ts
describe('viewport campgrounds', () => {
  const pin: PinFeature = {
    type: 'Feature',
    id: 7,
    geometry: { type: 'Point', coordinates: [-121, 40] },
    properties: { category: 'campground', agency: 'USFS' },
  };

  test('starts with none, and none requested', () => {
    expect(map()).toMatchObject({ viewportCampgrounds: [], campgroundsRequested: false });
  });

  test('records the pins and whether campgrounds were asked for', () => {
    map().setViewportCampgrounds([pin], true);

    expect(map().viewportCampgrounds).toEqual([pin]);
    expect(map().campgroundsRequested).toBe(true);
  });

  test('reset clears them', () => {
    map().setViewportCampgrounds([pin], true);
    map().reset();

    expect(map()).toMatchObject({ viewportCampgrounds: [], campgroundsRequested: false });
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd frontend && npm run test -- src/stores/mapStore.test.ts`
Expected: FAIL — `setViewportCampgrounds is not a function`.

- [ ] **Step 3: Add the state to the store**

In `frontend/src/stores/mapStore.ts`:

Add the import beside the existing type imports:

```ts
import type { PinFeature } from '@/map/pins';
```

Add to `MapState`, after `hiddenAgencies`:

```ts
  /**
   * The campground pins the viewport loop last returned, and whether campgrounds
   * were requested at all (false below the zoom gate). Published by
   * `features/map/useViewportPois` for the topbar's in-view list, which sits
   * across the feature boundary. With a route up these are the corridor's pins.
   */
  viewportCampgrounds: PinFeature[];
  campgroundsRequested: boolean;
```

Add to the action signatures, after `setAgencyHidden`:

```ts
  setViewportCampgrounds: (features: PinFeature[], requested: boolean) => void;
```

Add to `INITIAL_MAP`:

```ts
  viewportCampgrounds: [],
  campgroundsRequested: false,
```

Add `| 'setViewportCampgrounds'` to the `Omit<MapState, …>` union under `INITIAL_MAP`.

Add the implementation in `create`, after `setAgencyHidden`:

```ts
  setViewportCampgrounds: (viewportCampgrounds, campgroundsRequested) =>
    set({ viewportCampgrounds, campgroundsRequested }),
```

- [ ] **Step 4: Run the store test**

Run: `cd frontend && npm run test -- src/stores/mapStore.test.ts`
Expected: PASS.

- [ ] **Step 5: Write the failing MapView test**

Append a describe to `frontend/src/features/map/MapView.test.tsx` (after the `'the viewport request'` describe):

```ts
describe('the in-view campgrounds', () => {
  test('are published to the store once campgrounds are requested', async () => {
    poiResponses = [
      collection([pin(9, 'tesla_supercharger')]),
      collection([pin(1, 'campground', 'USFS'), pin(9, 'tesla_supercharger')]),
    ];
    await renderMap();
    await waitFor(() => expect(poiRequests()).toHaveLength(1));
    expect(useMapStore.getState().campgroundsRequested).toBe(false);
    expect(useMapStore.getState().viewportCampgrounds).toEqual([]);

    await panTo(BAY_AREA, 6);

    await waitFor(() =>
      expect(useMapStore.getState().viewportCampgrounds.map((f) => f.id)).toEqual([1]),
    );
    expect(useMapStore.getState().campgroundsRequested).toBe(true);
  });
});
```

- [ ] **Step 6: Run it to see it fail**

Run: `cd frontend && npm run test -- src/features/map/MapView.test.tsx -t "in-view campgrounds"`
Expected: FAIL — `viewportCampgrounds` stays `[]`.

- [ ] **Step 7: Publish from the viewport loop**

In `frontend/src/features/map/useViewportPois.ts`, replace the tail of the hook (from `const buckets = useMemo(…)` to the end) with:

```ts
  const buckets = useMemo(() => bucketPins(features), [features]);
  const counts = useMemo(() => countPins(buckets), [buckets]);
  const agencies = useMemo(() => agencyCounts(buckets.cg.features), [buckets]);

  // A route supplies campgrounds whatever the zoom, so the hint would otherwise
  // tell the user to zoom in while the legend lists the corridor's agencies
  // right below it.
  const campgroundsRequested = routeActive || (request?.campgroundsRequested ?? false);

  const setViewportCampgrounds = useMapStore((s) => s.setViewportCampgrounds);
  useEffect(() => {
    setViewportCampgrounds(buckets.cg.features, campgroundsRequested);
  }, [buckets, campgroundsRequested, setViewportCampgrounds]);

  return { buckets, counts, agencies, campgroundsRequested };
}
```

- [ ] **Step 8: Run the map tests**

Run: `cd frontend && npm run test -- src/features/map/MapView.test.tsx`
Expected: PASS, including the new test.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/stores/mapStore.ts frontend/src/stores/mapStore.test.ts frontend/src/features/map/useViewportPois.ts frontend/src/features/map/MapView.test.tsx
git commit -m "feat(map): publish the viewport's campgrounds to the store"
```

---

### Task 2: Cards for campgrounds with no route

**Files:**
- Modify: `frontend/src/map/viewport.ts`
- Test: `frontend/src/map/viewport.test.ts`
- Modify: `frontend/src/features/trip/trip-cards.ts`
- Modify: `frontend/src/features/trip/TripResults.tsx:127` (one guard, so typecheck stays green)
- Test: `frontend/src/features/trip/trip-cards.test.ts`

**Interfaces:**
- Consumes: `distanceKm(lat1, lon1, lat2, lon2)` from `@/lib/geo`; `ViewportBbox` (`[west, south, east, north]`) from `@/map/viewport`.
- Produces:
  - `bboxCenter(bbox: ViewportBbox): MapCenter` in `@/map/viewport`, `MapCenter = { lng: number; lat: number }`.
  - `TripCard` gains `checkable: boolean`, `rating: number | null`; `routeKm` becomes `number | null` (null = no route).
  - `viewportCardsFromFeatures(features, center: MapCenter | null): TripCard[]`, nearest the centre first.
  - `hydrateCard` fills `checkable` from `availability_supported === true` and `rating` from `rating.average`.

- [ ] **Step 1: Write the failing `bboxCenter` test**

Append to `frontend/src/map/viewport.test.ts` (add `bboxCenter` to the import from `./viewport`):

```ts
describe('bboxCenter', () => {
  test('is the midpoint of the box', () => {
    expect(bboxCenter([-124, 32, -114, 42])).toEqual({ lng: -119, lat: 37 });
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd frontend && npm run test -- src/map/viewport.test.ts`
Expected: FAIL — `bboxCenter` is not exported.

- [ ] **Step 3: Add `bboxCenter`**

Append to `frontend/src/map/viewport.ts`:

```ts
export interface MapCenter {
  lng: number;
  lat: number;
}

/** The midpoint of a bbox; what "nearest" means for a list with no route. */
export function bboxCenter([west, south, east, north]: ViewportBbox): MapCenter {
  return { lng: (west + east) / 2, lat: (south + north) / 2 };
}
```

- [ ] **Step 4: Run it**

Run: `cd frontend && npm run test -- src/map/viewport.test.ts`
Expected: PASS.

- [ ] **Step 5: Write the failing card tests**

In `frontend/src/features/trip/trip-cards.test.ts`:

Update the imports and the `card()` fixture:

```ts
import {
  hydrateCard,
  tripCardsFromFeatures,
  viewportCardsFromFeatures,
  visibleCards,
  type TripCard,
} from './trip-cards';
```

```ts
const card = (over: Partial<TripCard> = {}): TripCard => ({
  id: 1,
  name: 'Campground',
  sub: '',
  location: '',
  agency: 'WA Parks',
  lng: -122.4,
  lat: 48.1,
  routeKm: 50,
  distKm: 60,
  checkable: false,
  rating: null,
  hydrated: false,
  ...over,
});
```

Append:

```ts
describe('viewportCardsFromFeatures', () => {
  test('orders cards nearest the map centre first, with no route distance', () => {
    const near = slim(1, -122.4, 48.1);
    const far = slim(2, -122.4, 49.0);

    const cards = viewportCardsFromFeatures([far, near], { lng: -122.4, lat: 48.0 });

    expect(cards.map((c) => c.id)).toEqual([1, 2]);
    expect(cards[0]!.routeKm).toBeNull();
    expect(cards[0]!.distKm).toBeGreaterThan(0);
    expect(cards[0]!.distKm).toBeLessThan(cards[1]!.distKm);
    expect(cards[0]!.agency).toBe('WA Parks');
  });

  test('keeps the wire order when the centre is unknown', () => {
    const cards = viewportCardsFromFeatures([slim(2, -122.4, 49.0), slim(1, -122.4, 48.1)], null);

    expect(cards.map((c) => c.id)).toEqual([2, 1]);
    expect(cards.every((c) => c.distKm === 0)).toBe(true);
  });

  test('drops a feature with no id or no point', () => {
    const noId = { type: 'Feature' as const, geometry: { type: 'Point' as const, coordinates: [-122, 48] }, properties: {} };
    const noPoint = { type: 'Feature' as const, id: 3, geometry: null, properties: {} };

    expect(viewportCardsFromFeatures([noId, noPoint], null)).toEqual([]);
  });
});

describe('hydrateCard: availability and rating', () => {
  test('reads whether availability can be checked, and the rating', () => {
    const hydrated = hydrateCard(card(), {
      name: 'Bowman Bay',
      availability_supported: true,
      rating: { average: 4.6, count: 12 },
    });

    expect(hydrated.checkable).toBe(true);
    expect(hydrated.rating).toBe(4.6);
  });

  test('treats a missing flag as not checkable and a missing rating as none', () => {
    const hydrated = hydrateCard(card(), { name: 'Bowman Bay' });

    expect(hydrated.checkable).toBe(false);
    expect(hydrated.rating).toBeNull();
  });
});
```

- [ ] **Step 6: Run them to see them fail**

Run: `cd frontend && npm run test -- src/features/trip/trip-cards.test.ts`
Expected: FAIL — `viewportCardsFromFeatures` is not exported; `checkable` is undefined.

- [ ] **Step 7: Implement in `trip-cards.ts`**

Replace the `TripCard` interface, `tripCardsFromFeatures`, and `hydrateCard` in `frontend/src/features/trip/trip-cards.ts` with the following (keep the file header, `SlimFeature`, `CardFilter`, and `visibleCards` as they are; add `import type { MapCenter } from '@/map/viewport';`):

```ts
export interface TripCard {
  id: string | number;
  /** "Campground" until the detail request lands. */
  name: string;
  /** The type label, e.g. "Standard campground". */
  sub: string;
  /** State or country, shown to the right of the name. */
  location: string;
  agency: string;
  lng: number;
  lat: number;
  /** Kilometres along the route — the sort key with a route; null without one. */
  routeKm: number | null;
  /** Straight-line kilometres from the origin, or from the map centre with no route. */
  distKm: number;
  /** True when the campground has a booking provider we can ask (`availability_supported`). */
  checkable: boolean;
  /** Average rating, when the detail carries one. */
  rating: number | null;
  hydrated: boolean;
}

const PLACEHOLDER_NAME = 'Campground';

interface CardBase {
  id: string | number;
  lng: number;
  lat: number;
  agency: string;
}

/** What a slim feature contributes to a card, or null when it cannot be one. */
function cardBaseOf(feature: SlimFeature): CardBase | null {
  const id = feature?.id ?? (feature?.properties?.id as string | number | undefined);
  if (id == null) return null;
  const coordinates = feature?.geometry?.coordinates;
  if (!Array.isArray(coordinates)) return null;
  const [lng, lat] = coordinates as [unknown, unknown];
  if (typeof lng !== 'number' || !Number.isFinite(lng)) return null;
  if (typeof lat !== 'number' || !Number.isFinite(lat)) return null;
  return { id, lng, lat, agency: (feature.properties?.agency as string | undefined) || '' };
}

function placeholderCard(base: CardBase, routeKm: number | null, distKm: number): TripCard {
  return {
    ...base,
    name: PLACEHOLDER_NAME,
    sub: '',
    location: '',
    routeKm,
    distKm,
    checkable: false,
    rating: null,
    hydrated: false,
  };
}

/**
 * Placeholder cards from a fresh corridor response, in the order a driver meets them.
 *
 * Features with no id or no usable coordinates are dropped rather than rendered: the
 * id is what hydration and the click-through both need, and a card with neither is a
 * row that cannot do anything.
 */
export function tripCardsFromFeatures(
  features: readonly SlimFeature[] | null | undefined,
  origin: TripStop | null | undefined,
  routeIndex: RouteIndex | null,
): TripCard[] {
  const cards: TripCard[] = [];
  for (const feature of features ?? []) {
    const base = cardBaseOf(feature);
    if (!base) continue;
    cards.push(
      placeholderCard(
        base,
        distanceAlongRouteKm(routeIndex, base.lng, base.lat),
        origin ? distanceKm(origin.lat, origin.lng, base.lat, base.lng) : 0,
      ),
    );
  }
  // The order the driver encounters them, which is the only ordering that makes the
  // list useful — see `route-index.ts`.
  return cards.sort((a, b) => (a.routeKm ?? 0) - (b.routeKm ?? 0));
}

/**
 * Placeholder cards for the campgrounds in view, nearest the map centre first.
 * Without a centre (no viewport reported yet) the wire order stands.
 */
export function viewportCardsFromFeatures(
  features: readonly SlimFeature[] | null | undefined,
  center: MapCenter | null,
): TripCard[] {
  const cards: TripCard[] = [];
  for (const feature of features ?? []) {
    const base = cardBaseOf(feature);
    if (!base) continue;
    const distKm = center ? distanceKm(center.lat, center.lng, base.lat, base.lng) : 0;
    cards.push(placeholderCard(base, null, distKm));
  }
  return center ? cards.sort((a, b) => a.distKm - b.distKm) : cards;
}

/** Fold a hydrated POI's flattened properties into its placeholder card. */
export function hydrateCard(
  card: TripCard,
  properties: Record<string, unknown> | null | undefined,
): TripCard {
  const p = properties ?? {};
  const rating = p.rating as { average?: unknown } | null | undefined;
  return {
    ...card,
    name: (p.name as string | undefined) || PLACEHOLDER_NAME,
    sub: (p.typeLabel as string | undefined) || '',
    location: (p.state as string | undefined) || (p.country as string | undefined) || '',
    agency: (p.agency as string | undefined) || card.agency,
    checkable: p.availability_supported === true,
    rating: typeof rating?.average === 'number' ? rating.average : null,
    hydrated: true,
  };
}
```

- [ ] **Step 8: Guard the one existing consumer of `routeKm`**

In `frontend/src/features/trip/TripResults.tsx`, change the meta line so `null` cannot reach the formatter (Task 4 replaces this block entirely; this keeps typecheck green now):

```tsx
                  <span className="tb-card-meta">
                    {card.routeKm != null ? (
                      <span className="tb-card-dist">{formatDistanceAlongRoute(card.routeKm)}</span>
                    ) : null}
                  </span>
```

- [ ] **Step 9: Run the card tests and the typecheck**

Run: `cd frontend && npm run test -- src/features/trip/trip-cards.test.ts && npm run typecheck`
Expected: PASS; typecheck clean.

- [ ] **Step 10: Commit**

```bash
git add frontend/src/map/viewport.ts frontend/src/map/viewport.test.ts frontend/src/features/trip/trip-cards.ts frontend/src/features/trip/trip-cards.test.ts frontend/src/features/trip/TripResults.tsx
git commit -m "feat(trip): cards for campgrounds in view, with no route to measure along"
```

---

### Task 3: Site counts and the in-view copy

**Files:**
- Create: `frontend/src/features/trip/site-counts.ts`
- Create: `frontend/src/features/trip/site-counts.test.ts`
- Create: `frontend/src/features/trip/useSiteCounts.ts`
- Modify: `frontend/src/lib/strings.ts` (append)

**Interfaces:**
- Consumes: `Campsite`, `fetchPoiCampsites(poiId, { signal })` → `PoiCampsitesResponse` (`{ campsites: Campsite[] }`) from `@/api/campsite-api`; `queryKeys.campsites.forPoi(id)` from `@/queries/keys` (the same key `useCampsites` caches the full response under, so this hook caches the full response too and derives counts in a memo); `TripCard` from `./trip-cards`.
- Produces:
  - `SiteCounts = { total: number }`; `siteCountsOf(campsites: readonly Campsite[] | null | undefined): SiteCounts`.
  - `SiteCountsById = ReadonlyMap<string, SiteCounts>` keyed by `String(card.id)`; `useSiteCounts(cards: readonly TripCard[]): SiteCountsById`.
  - `inViewCopy` in `@/lib/strings`: `heading`, `sites(total)`, `checkableCount(n)`, `notCheckable`, `zoomIn`, `none`.

- [ ] **Step 1: Write the failing test**

Create `frontend/src/features/trip/site-counts.test.ts`:

```ts
import { describe, expect, test } from 'vitest';
import type { Campsite } from '@/api/campsite-api';
import { inViewCopy } from '@/lib/strings';
import { siteCountsOf } from './site-counts';

const site = (id: number): Campsite => ({
  id,
  campground_id: 11,
  name: `Site ${id}`,
  kind: 'tent',
  kind_label: 'Tent',
  equipment: [],
  attributes: [],
  data_provider: 'recgov',
  data_provider_ref: String(id),
});

describe('siteCountsOf', () => {
  test('counts the catalog', () => {
    expect(siteCountsOf([site(1), site(2), site(3)])).toEqual({ total: 3 });
  });

  test('an absent catalog counts as none', () => {
    expect(siteCountsOf(undefined)).toEqual({ total: 0 });
    expect(siteCountsOf(null)).toEqual({ total: 0 });
  });
});

describe('inViewCopy.sites', () => {
  test('pluralises', () => {
    expect(inViewCopy.sites(1)).toBe('1 site');
    expect(inViewCopy.sites(54)).toBe('54 sites');
    expect(inViewCopy.sites(0)).toBe('0 sites');
  });
});
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd frontend && npm run test -- src/features/trip/site-counts.test.ts`
Expected: FAIL — cannot resolve `./site-counts`; `inViewCopy` is not exported.

- [ ] **Step 3: Create `site-counts.ts`**

```ts
// How many sites a campground has, from its campsite catalog.
//
// `GET /api/pois/{id}` carries no site count, so the in-view card counts the
// catalog the availability grid already fetches. M2 extends this with per-kind
// counts and the largest `max_people`.
import type { Campsite } from '@/api/campsite-api';

export interface SiteCounts {
  total: number;
}

export function siteCountsOf(campsites: readonly Campsite[] | null | undefined): SiteCounts {
  return { total: campsites?.length ?? 0 };
}
```

- [ ] **Step 4: Add `inViewCopy` to `strings.ts`**

Append to `frontend/src/lib/strings.ts`:

```ts
/**
 * The topbar's list of campgrounds in view, before any dates are chosen.
 * "Checkable online" is the one availability word here: the campground has a
 * booking provider we can ask (`availability_supported`).
 */
export const inViewCopy = {
  heading: 'Campgrounds in view',
  /** The card's count line: the whole catalog, until a site type narrows it (M2). */
  sites: (total: number) => (total === 1 ? '1 site' : `${total} sites`),
  checkableCount: (checkable: number) => `${checkable} checkable online`,
  notCheckable: 'Not checkable online',
  zoomIn: 'Zoom in to load campgrounds.',
  none: 'No campgrounds in view — pan or zoom out to find some.',
} as const;
```

- [ ] **Step 5: Run the test**

Run: `cd frontend && npm run test -- src/features/trip/site-counts.test.ts`
Expected: PASS.

- [ ] **Step 6: Create the hook**

Create `frontend/src/features/trip/useSiteCounts.ts`:

```ts
// One campsites fetch per in-view card, cached under the drawer's own key so
// opening a card after the list has loaded is a cache hit.
import { useMemo } from 'react';
import { useQueries } from '@tanstack/react-query';
import { fetchPoiCampsites } from '@/api/campsite-api';
import { queryKeys } from '@/queries/keys';
import { siteCountsOf, type SiteCounts } from './site-counts';
import type { TripCard } from './trip-cards';

/** The catalog changes on an ETL cadence; matches `useCampsites`. */
const CATALOG_STALE_MS = 5 * 60_000;

export type SiteCountsById = ReadonlyMap<string, SiteCounts>;

export function useSiteCounts(cards: readonly TripCard[]): SiteCountsById {
  const queries = useQueries({
    queries: cards.map((card) => ({
      // The full response, not the counts: `useCampsites` reads this key and
      // expects the catalog, so the derived shape must not be cached under it.
      queryKey: queryKeys.campsites.forPoi(card.id),
      queryFn: ({ signal }: { signal: AbortSignal }) => fetchPoiCampsites(card.id, { signal }),
      staleTime: CATALOG_STALE_MS,
      retry: false,
    })),
  });

  // One scalar dep; `useQueries` returns one entry per card and a dep list has to
  // be a constant size (same move as `useTripCards`).
  const signature = cards
    .map((card, index) => `${card.id}:${queries[index]?.dataUpdatedAt ?? 0}`)
    .join('|');

  return useMemo(() => {
    const counts = new Map<string, SiteCounts>();
    cards.forEach((card, index) => {
      const data = queries[index]?.data;
      if (data) counts.set(String(card.id), siteCountsOf(data.campsites));
    });
    return counts;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature]);
}
```

- [ ] **Step 7: Typecheck**

Run: `cd frontend && npm run typecheck`
Expected: clean. (The hook has no consumer yet; its behaviour is covered through `TopBar.test.tsx` in Task 4.)

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/trip/site-counts.ts frontend/src/features/trip/site-counts.test.ts frontend/src/features/trip/useSiteCounts.ts frontend/src/lib/strings.ts
git commit -m "feat(trip): site counts per campground, and the in-view list's copy"
```

---

### Task 4: The results list shows the campgrounds in view

**Files:**
- Modify: `frontend/src/features/trip/TripResults.tsx`
- Modify: `frontend/src/features/trip/TopBar.tsx:40-52,240-262`
- Test: `frontend/src/features/trip/TopBar.test.tsx:733-887`

**Interfaces:**
- Consumes: `viewportCampgrounds`, `campgroundsRequested`, `viewport` from `useMapStore`; `bboxCenter` from `@/map/viewport`; `viewportCardsFromFeatures`, `TripCard` from `./trip-cards`; `useTripCards`; `useSiteCounts`, `SiteCountsById`; `inViewCopy`.
- Produces: `TripResultsProps` as a discriminated union on `variant`:
  - `{ variant: 'route'; cards; loading: boolean; corridorMiles: number; onCorridorMilesChange: (miles: number) => void }`
  - `{ variant: 'viewport'; cards; siteCounts: SiteCountsById; campgroundsRequested: boolean }`

- [ ] **Step 1: Rewrite the failing no-route test and add the in-view tests**

In `frontend/src/features/trip/TopBar.test.tsx`, inside `describe('the results list', …)`, replace the test `'and says nothing at all without a route'` with:

```ts
  test('has no corridor slider without a route', () => {
    mount();

    expect(document.querySelector('#tb-corridor')).toBeNull();
  });
```

Then append a new describe after `'the results list'`:

```ts
describe('the in-view list', () => {
  const IN_VIEW = [
    {
      type: 'Feature',
      id: 11,
      geometry: { type: 'Point', coordinates: [-122.64, 48.4] },
      properties: { category: 'campground', agency: 'WA Parks' },
    },
    {
      type: 'Feature',
      id: 22,
      geometry: { type: 'Point', coordinates: [-122.35, 47.7] },
      properties: { category: 'campground', agency: 'USFS' },
    },
  ];

  const DETAILS: Record<string, unknown> = {
    11: {
      type: 'Feature',
      id: 11,
      geometry: { type: 'Point', coordinates: [-122.64, 48.4] },
      properties: {
        name: 'Bowman Bay',
        address: { state: 'WA' },
        availability_supported: true,
        rating: { average: 4.6, count: 12 },
      },
    },
    22: {
      type: 'Feature',
      id: 22,
      geometry: { type: 'Point', coordinates: [-122.35, 47.7] },
      properties: { name: 'Denny Creek', country: 'US' },
    },
  };

  const site = (id: number) => ({
    id,
    campground_id: 0,
    name: `Site ${id}`,
    kind: 'tent',
    kind_label: 'Tent',
    equipment: [],
    attributes: [],
    data_provider: 'recgov',
    data_provider_ref: String(id),
  });
  const CAMPSITES: Record<string, unknown[]> = {
    11: [site(1), site(2), site(3)],
    22: [site(4)],
  };

  /** A viewport with two campgrounds in it, centred nearer Denny Creek. */
  const withViewport = () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        urls.push(url);
        const campsites = /\/api\/pois\/(\d+)\/campsites$/.exec(url);
        if (campsites) return json({ campsites: CAMPSITES[campsites[1]!] });
        const detail = /\/api\/pois\/(\d+)$/.exec(url);
        if (detail) return json(DETAILS[detail[1]!]);
        if (url.startsWith('/api/route')) return json(ROUTE_BODY);
        if (url.startsWith('/api/pois/on-route')) return json({ type: 'FeatureCollection', features: [] });
        return json({ results: [] });
      }),
    );
    useMapStore.setState({
      viewport: { bbox: [-122.7, 47.5, -122.3, 48.0], zoom: 9 },
      viewportCampgrounds: IN_VIEW as never,
      campgroundsRequested: true,
    });
  };

  test('lists the campgrounds in view without a route, nearest the centre first', async () => {
    withViewport();
    mount();

    expect(screen.getByText('Campgrounds in view')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());
    await waitFor(() => expect(screen.getByText('Denny Creek')).toBeInTheDocument());
    const names = screen.getAllByRole('button', { name: /Denny Creek|Bowman Bay/ });
    expect(names[0]).toHaveTextContent('Denny Creek');
  });

  test('says how many sites each has, its rating, and which cannot be checked online', async () => {
    withViewport();
    mount();

    await waitFor(() => expect(screen.getByText('3 sites')).toBeInTheDocument());
    expect(screen.getByText('1 site')).toBeInTheDocument();
    expect(screen.getByText('★ 4.6')).toBeInTheDocument();
    expect(screen.getAllByText('Not checkable online')).toHaveLength(1);
    expect(screen.getByRole('button', { name: /Denny Creek/ })).toHaveTextContent('Not checkable online');
  });

  test('counts what is in view and how many can be checked', async () => {
    withViewport();
    mount();

    await waitFor(() => expect(screen.getByText('· 2 · 1 checkable online')).toBeInTheDocument());
  });

  test('says to zoom in before campgrounds are requested', () => {
    withViewport();
    useMapStore.setState({ viewportCampgrounds: [], campgroundsRequested: false });
    mount();

    expect(screen.getByText('Zoom in to load campgrounds.')).toBeInTheDocument();
  });

  test('says so when the view holds none', () => {
    withViewport();
    useMapStore.setState({ viewportCampgrounds: [] });
    mount();

    expect(screen.getByText('No campgrounds in view — pan or zoom out to find some.')).toBeInTheDocument();
  });

  test('a card click flies to it and opens its drawer', async () => {
    withViewport();
    mount();
    await waitFor(() => expect(screen.getByText('Bowman Bay')).toBeInTheDocument());

    await act(async () => {
      screen.getByRole('button', { name: /Bowman Bay/ }).click();
    });

    expect(fakeMap.flyToCalls.at(-1)).toMatchObject({ center: [-122.64, 48.4], zoom: 13 });
    expect(useMapStore.getState().selectedPoiId).toBe(11);
  });

  test('gives way to the route list once a trip is whole', async () => {
    withViewport();
    useTripStore.setState({
      mode: 'directions',
      stops: [
        { name: 'Seattle', lng: -122.33, lat: 47.6 },
        { name: 'Bowman Bay', lng: -122.65, lat: 48.41 },
      ],
    });
    mount();

    await waitFor(() => expect(screen.getByText('Campgrounds along route')).toBeInTheDocument());
    expect(screen.queryByText('Campgrounds in view')).toBeNull();
  });
});
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd frontend && npm run test -- src/features/trip/TopBar.test.tsx -t "in-view list"`
Expected: FAIL — no "Campgrounds in view" heading.

- [ ] **Step 3: Give `TripResults` its two variants**

Replace `frontend/src/features/trip/TripResults.tsx` wholesale:

```tsx
// The campgrounds list: along the route when there is one, in view when there is not.
//
// Collapse state stays local and defaults closed on phones so results do not cover
// the route immediately after it is computed.
import { useState } from 'react';
import { Button, EmptyState, Icon } from '@ui';
import { token } from '@tokens';
import { inViewCopy } from '@/lib/strings';
import { useMapContext } from '@/map/context';
import { useMapStore } from '@/stores/mapStore';
import { CorridorSlider } from './CorridorSlider';
import { formatDistanceAlongRoute } from './route-summary';
import { visibleCards, type TripCard } from './trip-cards';
import type { SiteCountsById } from './useSiteCounts';
import { shouldAutoFocus } from '@/domain/trip/viewport';

/** Where a card click puts the camera: tight enough to see the pin, wide enough to place it. */
const CARD_FLY_ZOOM = 13;
const FLY_SPEED = 1.6;

const ROUTE_HEADING = 'Campgrounds along route';
const RATING_PREFIX = '★';

interface CommonProps {
  cards: readonly TripCard[];
}

interface RouteProps extends CommonProps {
  variant: 'route';
  /** True while the corridor request is in flight, for the count line. */
  loading: boolean;
  corridorMiles: number;
  onCorridorMilesChange: (miles: number) => void;
}

interface ViewportProps extends CommonProps {
  variant: 'viewport';
  siteCounts: SiteCountsById;
  /** False below the zoom gate, where the server sends no campgrounds. */
  campgroundsRequested: boolean;
}

export type TripResultsProps = RouteProps | ViewportProps;

export function TripResults(props: TripResultsProps) {
  const { cards } = props;
  const { map } = useMapContext();
  const hiddenAgencies = useMapStore((s) => s.hiddenAgencies);
  const campgroundsHidden = useMapStore((s) => s.hiddenOverlays.includes('cg'));
  const setOverlayHidden = useMapStore((s) => s.setOverlayHidden);
  const selectPoi = useMapStore((s) => s.selectPoi);

  // Collapsed on a phone, expanded where there is room. `shouldAutoFocus` answers the
  // same question — "is this a desktop" — and having one reader of the breakpoint keeps
  // the two from disagreeing.
  const [collapsed, setCollapsed] = useState(() => !shouldAutoFocus());

  const visible = visibleCards(cards, { hiddenAgencies, campgroundsHidden });
  const total = cards.length;

  const openCard = (card: TripCard) => {
    // Fly, then select. The drawer reads `selectedPoiId` and hydrates from the id.
    map?.flyTo({ center: [card.lng, card.lat], zoom: CARD_FLY_ZOOM, speed: FLY_SPEED });
    selectPoi(card.id);
  };

  return (
    <div className={`tb-results visible${collapsed ? ' collapsed' : ''}`} id="tb-results">
      <div
        className="tb-results-head"
        role="button"
        tabIndex={0}
        aria-expanded={!collapsed}
        onClick={() => setCollapsed((current) => !current)}
        onKeyDown={(event) => {
          if (event.key !== 'Enter' && event.key !== ' ') return;
          event.preventDefault();
          setCollapsed((current) => !current);
        }}
      >
        {props.variant === 'route' ? ROUTE_HEADING : inViewCopy.heading}
        <span className="tb-results-count">{countLine(props, visible, total)}</span>
        {/* Points up when expanded; `.tb-results.collapsed` rotates it. */}
        <Icon name="chevron-up" className="tb-results-chevron" aria-hidden="true" />
      </div>

      <div className="tb-results-body">
        {props.variant === 'route' ? (
          <div className="tb-results-controls">
            <CorridorSlider miles={props.corridorMiles} onChange={props.onCorridorMilesChange} />
          </div>
        ) : null}

        <div className="tb-results-cards" id="tb-results-cards">
          {visible.length === 0 ? (
            campgroundsHidden ? (
              <EmptyState
                icon="eye-off"
                title="Campgrounds are switched off"
                body={`There are ${total} in view — the Campgrounds layer is turned off, so none are drawn.`}
                actions={
                  <Button variant="primary" size="sm" onClick={() => setOverlayHidden('cg', false)}>
                    Turn campgrounds back on
                  </Button>
                }
              />
            ) : (
              <div className="tb-card-empty">{emptyCopy(props, total)}</div>
            )
          ) : (
            visible.map((card) => (
              <button
                type="button"
                className="tb-card"
                key={String(card.id)}
                data-id={String(card.id)}
                onClick={() => openCard(card)}
              >
                <span className="tb-card-dot" style={{ background: token('--rt-layer-cg') }} />
                <span className="tb-card-body">
                  <span className="tb-card-head">
                    <span className="tb-card-name">{card.name}</span>
                    {card.location ? (
                      <span className="tb-card-location">{card.location}</span>
                    ) : null}
                  </span>
                  {subLine(props, card) ? <span className="tb-card-sub">{subLine(props, card)}</span> : null}
                  <span className="tb-card-meta">
                    {props.variant === 'route' ? (
                      <RouteMeta card={card} />
                    ) : (
                      <ViewportMeta card={card} siteCounts={props.siteCounts} />
                    )}
                  </span>
                </span>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

/** "3 of 12" only while something is filtered out — otherwise the second number is noise. */
function countLine(props: TripResultsProps, visible: TripCard[], total: number): string {
  const count = visible.length === total ? String(total) : `${visible.length} of ${total}`;
  if (props.variant === 'route') return `· ${count}`;
  // The checkable count waits for every visible card, so it never counts up from 0.
  if (visible.length === 0 || !visible.every((card) => card.hydrated)) return `· ${count}`;
  const checkable = visible.filter((card) => card.checkable).length;
  return `· ${count} · ${inViewCopy.checkableCount(checkable)}`;
}

function emptyCopy(props: TripResultsProps, total: number): string {
  if (props.variant === 'route') {
    if (props.loading) return 'Looking for campgrounds along the route…';
    if (total === 0) return 'Pan the map or widen the corridor to find campgrounds.';
  } else {
    if (!props.campgroundsRequested) return inViewCopy.zoomIn;
    if (total === 0) return inViewCopy.none;
  }
  return 'All campgrounds hidden — re-enable a category in the legend.';
}

/** The route list reads the type; the in-view list reads the agency, as the design does. */
function subLine(props: TripResultsProps, card: TripCard): string {
  return props.variant === 'route' ? card.sub : card.agency || card.sub;
}

function RouteMeta({ card }: { card: TripCard }) {
  return card.routeKm != null ? (
    <span className="tb-card-dist">{formatDistanceAlongRoute(card.routeKm)}</span>
  ) : null;
}

function ViewportMeta({ card, siteCounts }: { card: TripCard; siteCounts: SiteCountsById }) {
  const counts = siteCounts.get(String(card.id));
  return (
    <>
      {counts ? <span>{inViewCopy.sites(counts.total)}</span> : null}
      {card.rating != null ? <span>{`${RATING_PREFIX} ${card.rating.toFixed(1)}`}</span> : null}
      {card.hydrated && !card.checkable ? <span>{inViewCopy.notCheckable}</span> : null}
    </>
  );
}
```

- [ ] **Step 4: Wire the topbar**

In `frontend/src/features/trip/TopBar.tsx`:

Add imports (replace the existing `import { tripCardsFromFeatures } from './trip-cards';` with the third line):

```ts
import { bboxCenter } from '@/map/viewport';
import { useMapStore } from '@/stores/mapStore';
import { tripCardsFromFeatures, viewportCardsFromFeatures, type TripCard } from './trip-cards';
import { useSiteCounts } from './useSiteCounts';
```

Add beside `NO_ACTIVE_RESULT`:

```ts
/** Stable empty, so the in-view pipeline does nothing while a route owns the list. */
const NO_CARDS: TripCard[] = [];
```

After `const shareable = allStopsFilled(planner.stops);` add:

```ts
  // Which list the panel shows: the corridor's once the trip is whole and routed,
  // otherwise whatever is in view.
  const showRoute = shareable && !!route.route;

  // The in-view list: the viewport loop's campground pins, hydrated the same way as
  // the corridor's, plus one catalog fetch per card for the site count.
  const viewportCampgrounds = useMapStore((s) => s.viewportCampgrounds);
  const campgroundsRequested = useMapStore((s) => s.campgroundsRequested);
  const viewportBbox = useMapStore((s) => s.viewport?.bbox ?? null);
  const inViewPlaceholders = useMemo(
    () =>
      showRoute
        ? NO_CARDS
        : viewportCardsFromFeatures(viewportCampgrounds, viewportBbox ? bboxCenter(viewportBbox) : null),
    [showRoute, viewportCampgrounds, viewportBbox],
  );
  const inViewCards = useTripCards(inViewPlaceholders);
  const siteCounts = useSiteCounts(inViewCards);
```

Replace the results block at the bottom (the `{shareable && route.route ? (<TripResults …/>) : null}` and its comment) with:

```tsx
      {/* The results section owns the corridor slider, because the radius is a property
          of that list: it is what decides which campgrounds are in it. That nesting is
          also the DOM contract `SmokeTest.kt` asserts —
          `#tb-results .tb-results-body #tb-corridor` must be visible with a route.

          Without a route the same section lists what is in view instead. */}
      {showRoute ? (
        <TripResults
          variant="route"
          cards={cards}
          loading={corridor.isFetching}
          corridorMiles={planner.corridorMiles}
          onCorridorMilesChange={planner.setCorridorMiles}
        />
      ) : (
        <TripResults
          variant="viewport"
          cards={inViewCards}
          siteCounts={siteCounts}
          campgroundsRequested={campgroundsRequested}
        />
      )}
```

- [ ] **Step 5: Run the topbar tests**

Run: `cd frontend && npm run test -- src/features/trip/TopBar.test.tsx`
Expected: PASS, both the route-list describe and the new in-view describe.

- [ ] **Step 6: Run the map page tests too**

`MapView.test.tsx` renders `TopBar`, so the in-view list now mounts there and fetches `/api/pois/{id}` and `/api/pois/{id}/campsites` for any campground pin; the harness answers both with its static fallback, which hydrates to a placeholder card and zero sites. That must not break anything.

Run: `cd frontend && npm run test -- src/features/map/MapView.test.tsx`
Expected: PASS. If a test asserts on the total `requests` array rather than `poiRequests()`, filter it the same way.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/features/trip/TripResults.tsx frontend/src/features/trip/TopBar.tsx frontend/src/features/trip/TopBar.test.tsx
git commit -m "feat(trip): list the campgrounds in view when there is no route (#565 M1)"
```

---

### Task 5: Whole-suite verification and the issue note

**Files:**
- None new. Runs the gates `make test` runs for the frontend, then reports.

- [ ] **Step 1: Lint, typecheck, tests, build**

Run from `frontend/`:

```bash
npm run lint && npm run typecheck && npm run test && npm run build
```

Expected: all green. `lint` is the cross-feature import guard; a failure there means `features/trip` reached into `features/map` — route it through `@/stores` or `@/map` instead.

- [ ] **Step 2: The repo's CSS and colour guards**

Run from the repo root:

```bash
node scripts/check-color-tokens.mjs && node scripts/check-css-blocks.mjs && node scripts/check-token-usage.mjs
```

Expected: clean (M1 adds no CSS; this proves it).

- [ ] **Step 3: See it in the browser**

Start the dev stack and open the map page with no route. Zoomed out: the panel shows "Campgrounds in view" with "Zoom in to load campgrounds." Zoom past level 6 over a park: cards fill with names, "N sites", "★ x.x", and "Not checkable online" on the ones without a booking provider. Enter a two-stop trip: the heading flips to "Campgrounds along route" and the corridor slider returns. Take one screenshot of each state for the PR.

- [ ] **Step 4: Open the PR and note it on the issue**

```bash
git push -u origin feat/565-m1-campgrounds-in-view
gh pr create --title "feat(trip): list the campgrounds in view when there is no route (#565 M1)" --body-file <(cat <<'EOF'
M1 of #565: the topbar's results list shows the campgrounds in the current view without a route.

- The viewport loop publishes its campground pins to `mapStore`; the topbar builds cards from them, nearest the map centre first, and hydrates each from `GET /api/pois/{id}` the way corridor cards already do.
- Each card shows its site count (one cached `GET /api/pois/{id}/campsites` per card), its rating, and "Not checkable online" when it has no booking provider.
- The list head counts what is in view and how many can be checked once every card has hydrated.
- With a route the list is unchanged: same heading, same corridor slider, same smoke-test DOM contract.

No backend changes. Spec: `docs/superpowers/specs/2026-09-12-campground-availability-map-design.md`. Plan: `docs/superpowers/plans/2026-09-12-campground-availability-m1.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)
gh issue comment 565 --body "M1 is up for review: <PR URL>. The in-view list, site counts and the checkable-online tag; no backend changes."
```

Replace `<PR URL>` with the URL `gh pr create` prints.

---

## Self-review

**Spec coverage (M1 only).** Step 1's list without a route: Task 4. Cards with name, region, agency, rating, site count: Tasks 2–4 (region via `location`, agency via `subLine`, rating via `ViewportMeta`). "Checkable online" tag and head count: Tasks 2–4. Pins stay in the layer colour: nothing changes them. The "Filter campgrounds" pill and the mobile bottom sheet are M2 and M5 and are deliberately absent; the panel's existing phone behaviour (collapsed by default) stands in until M5.

**Placeholders.** None; every code step is complete.

**Type consistency.** `setViewportCampgrounds(features, requested)` (Task 1) is what `useViewportPois` calls (Task 1) and what `TopBar.test.tsx` seeds through `setState` (Task 4). `viewportCardsFromFeatures(features, center)` and `MapCenter` (Task 2) match `TopBar.tsx` (Task 4). `SiteCountsById` and `useSiteCounts(cards)` (Task 3) match `TripResults` and `TopBar` (Task 4). `TripCard.routeKm: number | null` (Task 2) is guarded in `RouteMeta` (Task 4).

**Known trade-off.** One campsites fetch per in-view card. It is cached five minutes under the drawer's key and the viewport ring cache keeps re-pans cheap, but a dense view can fire a few dozen catalog requests. Measure before batching; the spec's M3 will need a bulk shape anyway.
