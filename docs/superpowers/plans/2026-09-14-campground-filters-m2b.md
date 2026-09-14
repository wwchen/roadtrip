# Campground Filters in the Map View (M2b of #565) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The topbar's in-view campground list is fed by `POST /api/campgrounds/search` and `POST /api/campgrounds/details`, gains site-type / group-size / amenity filters whose state lives in a Zustand store, shows a match / no-data facet per active filter on every card, and offers an Add dates banner that stores the date window only.

**Architecture:** A `campgroundFilterStore` holds the filter and the date window. `useCampgroundSearch` posts the viewport as a GeoJSON polygon plus the filter and gets ids and counts; `useCampgroundSummaries` fetches the ids not already cached in one details call and fans the response into per-id TanStack entries. Cards render from `CampgroundSummaryDto`; a pure `facets.ts` derives the facet row; `FilterPanel` holds the controls. The M1 per-card detail + campsites pipeline for the in-view list is retired. The route list is untouched. No backend changes.

**Tech Stack:** React 18 + TypeScript, TanStack Query, Zustand, LDS via `@ui` (`SegmentedControl`, `Chip`, `Banner`, `Button`, `Icon`, `SeededTextField`), Vitest + Testing Library + jsdom, Storybook.

**Spec:** `docs/superpowers/specs/2026-09-12-campground-availability-map-design.md`, section "M2 design (2026-09-13, revised)" → "Frontend". The backend half (M2a) is merged; the generated types are in `frontend/src/api/generated/api-types.ts`.

## Global Constraints

- `features/<a>` never imports from `features/<b>`; shared state goes through `@/stores`, shared logic through `@/lib`, `@/map`, `@/api`, `@/queries`. `npm run lint` enforces this.
- Never edit `frontend/src/api/generated/api-types.ts`. The DTOs it carries for this work: `CampgroundSearchRequestDto`, `CampgroundSearchResponseDto`, `CampgroundDetailsRequestDto`, `CampgroundDetailsResponseDto`, `CampgroundSummaryDto`, `CampgroundFilterDto`, `BoundaryDto`, unions `CampsiteKind`, `AmenityKey`, `GeoJsonBoundaryType`.
- Labels for kinds and amenities are the backend's: a card renders `AmenityDto.label` as given; the four exposed amenity chips and the three exposed kinds use one local table in `lib/campground-vocab.ts` that mirrors the backend enum labels ("Toilets", "Showers", "Water", "Pets allowed"; "Tent", "RV", "Cabin").
- User-facing copy goes in `frontend/src/lib/strings.ts` as `filterCopy`; `inViewCopy` keeps what M1 put there.
- LDS controls are uncontrolled: seed once, mirror `onChange`, remount to reseed; conditionally rendered text fields use `SeededTextField`. Disable buttons, not fields.
- Colours are `--rt-*` tokens only; new CSS goes in `features/trip/topbar.css`; `node scripts/check-css-blocks.mjs` and `node scripts/check-token-usage.mjs` must pass.
- No inline magic constants. Comments short and rare. Tests assert on roles, labels and text.
- Every new component gets a Storybook story (Storybook is the catalog).
- Desktop layout only; mobile folding is M5.
- Commit messages follow `type(scope): sentence`, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Frontend commands run from `frontend/`: `npm run test -- <file>`, `npm run typecheck`, `npm run lint`, `npm run build`, `npm run build-storybook`.
- The worktree Bash guard rejects paths held in shell variables and compound git-adjacent commands; a hook denies the first Write/Edit to each file (re-issue the identical call).

---

## File map

| File | Change | Responsibility |
|---|---|---|
| `frontend/src/stores/campgroundFilterStore.ts` / `.test.ts` | create | Filter + date window state, selectors. |
| `frontend/src/lib/campground-vocab.ts` / `.test.ts` | create | Exposed kinds and amenities with backend labels. |
| `frontend/src/lib/strings.ts` | modify | `filterCopy`, `inViewCopy.loading`. |
| `frontend/src/api/campground-api.ts` | create | `searchCampgrounds`, `fetchCampgroundSummaries`. |
| `frontend/src/queries/keys.ts` | modify | `campgrounds.search`, `campgrounds.summary`, `campgrounds.summaries`. |
| `frontend/src/map/viewport.ts` / `.test.ts` | modify | `bboxBoundary(bbox): BoundaryDto`. |
| `frontend/src/features/trip/useCampgroundSearch.ts` / `.test.tsx` | create | Viewport + filter → ids and counts. |
| `frontend/src/features/trip/useCampgroundSummaries.ts` / `.test.tsx` | create | Bulk details with per-id cache reuse. |
| `frontend/src/features/trip/campground-cards.ts` / `.test.ts` | create | `InViewCard` from summaries; count line. |
| `frontend/src/features/trip/facets.ts` / `.test.ts` | create | Facet row per active filter. |
| `frontend/src/features/trip/GroupSizeStepper.tsx` / `.stories.tsx` | create | Minus / count / plus. |
| `frontend/src/features/trip/DateWindowFields.tsx` | create | Start / end date entry with Save. |
| `frontend/src/features/trip/FilterPanel.tsx` / `.stories.tsx` / `.test.tsx` | create | Pill, controls, banner. |
| `frontend/src/features/trip/TripResults.tsx` / `.stories.tsx` | modify | Viewport variant renders `InViewCard`s with facets and the new head. |
| `frontend/src/features/trip/TopBar.tsx` / `.test.tsx` | modify | Wire search → summaries → cards; mount the panel. |
| `frontend/src/features/trip/topbar.css` | modify | `.tb-filters*`, `.tb-stepper*`, `.tb-facet*`, `.tb-dates*`. |
| `frontend/src/features/trip/useSiteCounts.ts`, `site-counts.ts`, `site-counts.test.ts` | delete | Retired with the per-card pipeline. |
| `frontend/src/features/trip/trip-cards.ts` / `.test.ts` | modify | Remove `viewportCardsFromFeatures`; keep the route builder. |

---

### Task 1: Filter store, vocabulary, copy

**Files:**
- Create: `frontend/src/stores/campgroundFilterStore.ts`, `frontend/src/stores/campgroundFilterStore.test.ts`
- Create: `frontend/src/lib/campground-vocab.ts`, `frontend/src/lib/campground-vocab.test.ts`
- Modify: `frontend/src/lib/strings.ts` (append after `inViewCopy`)

**Interfaces:**
- Consumes: `CampsiteKind`, `AmenityKey`, `CampgroundFilterDto` from `@/api/generated/api-types`.
- Produces:
  - `useCampgroundFilterStore` with state `{ siteType: CampsiteKind | null; groupSize: number | null; amenities: AmenityKey[]; dateWindow: DateWindow | null }`, `interface DateWindow { start: string; end: string }` (ISO `YYYY-MM-DD`), actions `setSiteType(kind: CampsiteKind | null)`, `setGroupSize(size: number | null)`, `toggleAmenity(key: AmenityKey)`, `setDateWindow(window: DateWindow | null)`, `clearFilters()` (filters only, keeps dates), `reset()`.
  - Selectors `selectActiveFilterCount(s): number`, `selectFilterDto(s): CampgroundFilterDto | undefined` (undefined when no filter is active; omits empty fields).
  - Constants `MIN_GROUP_SIZE = 2`, `MAX_GROUP_SIZE = 12` exported from the store module.
  - `lib/campground-vocab.ts`: `FILTER_SITE_TYPES: readonly { value: CampsiteKind; label: string }[]` = tent/rv/cabin; `FILTER_AMENITIES: readonly { key: AmenityKey; label: string }[]` = toilets/showers/water/pets_allowed; `kindLabel(kind: CampsiteKind): string`; `amenityLabel(key: AmenityKey): string` (falls back to the key for keys outside the table).
  - `filterCopy` in `lib/strings.ts` (exact strings in Step 5) and `inViewCopy.loading`.

- [ ] **Step 1: Write the failing store test**

Create `frontend/src/stores/campgroundFilterStore.test.ts`:

```ts
import { beforeEach, describe, expect, test } from 'vitest';
import {
  MAX_GROUP_SIZE,
  MIN_GROUP_SIZE,
  selectActiveFilterCount,
  selectFilterDto,
  useCampgroundFilterStore,
} from './campgroundFilterStore';

const filters = () => useCampgroundFilterStore.getState();

beforeEach(() => filters().reset());

describe('initial state', () => {
  test('nothing is active and no dates are set', () => {
    expect(filters()).toMatchObject({ siteType: null, groupSize: null, amenities: [], dateWindow: null });
    expect(selectActiveFilterCount(filters())).toBe(0);
    expect(selectFilterDto(filters())).toBeUndefined();
  });
});

describe('filters', () => {
  test('site type, group size and amenities each count as one active filter', () => {
    filters().setSiteType('tent');
    filters().setGroupSize(4);
    filters().toggleAmenity('toilets');
    filters().toggleAmenity('showers');

    expect(selectActiveFilterCount(filters())).toBe(3);
    expect(selectFilterDto(filters())).toEqual({ site_type: 'tent', group_size: 4, amenities: ['toilets', 'showers'] });
  });

  test('toggling an amenity twice removes it and the dto omits the empty list', () => {
    filters().toggleAmenity('toilets');
    filters().toggleAmenity('toilets');

    expect(filters().amenities).toEqual([]);
    expect(selectFilterDto(filters())).toBeUndefined();
  });

  test('group size is clamped to the stepper range', () => {
    filters().setGroupSize(MAX_GROUP_SIZE + 5);
    expect(filters().groupSize).toBe(MAX_GROUP_SIZE);

    filters().setGroupSize(MIN_GROUP_SIZE - 1);
    expect(filters().groupSize).toBeNull();
  });

  test('clearFilters keeps the date window', () => {
    filters().setSiteType('rv');
    filters().setDateWindow({ start: '2026-09-11', end: '2026-09-13' });

    filters().clearFilters();

    expect(filters().siteType).toBeNull();
    expect(filters().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
  });
});
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npm run test -- src/stores/campgroundFilterStore.test.ts`
Expected: FAIL, module not found.

- [ ] **Step 3: Write the store**

Create `frontend/src/stores/campgroundFilterStore.ts`:

```ts
// The campground filter and the date window, shared by the topbar's list (M2),
// the availability poll (M3) and the map's pins and legend (M4). Session-only.
import { create } from 'zustand';
import type { AmenityKey, CampgroundFilterDto, CampsiteKind } from '@/api/generated/api-types';

/** Stepper range; below the minimum the group filter is simply off. */
export const MIN_GROUP_SIZE = 2;
export const MAX_GROUP_SIZE = 12;

export interface DateWindow {
  /** ISO calendar dates, `YYYY-MM-DD`. */
  start: string;
  end: string;
}

export interface CampgroundFilterState {
  siteType: CampsiteKind | null;
  groupSize: number | null;
  amenities: AmenityKey[];
  dateWindow: DateWindow | null;
  setSiteType: (kind: CampsiteKind | null) => void;
  setGroupSize: (size: number | null) => void;
  toggleAmenity: (key: AmenityKey) => void;
  setDateWindow: (window: DateWindow | null) => void;
  /** The three catalog filters; the date window is a separate decision and stays. */
  clearFilters: () => void;
  reset: () => void;
}

const INITIAL_FILTERS = {
  siteType: null,
  groupSize: null,
  amenities: [],
  dateWindow: null,
} satisfies Omit<
  CampgroundFilterState,
  'setSiteType' | 'setGroupSize' | 'toggleAmenity' | 'setDateWindow' | 'clearFilters' | 'reset'
>;

function clampGroupSize(size: number | null): number | null {
  if (size == null || size < MIN_GROUP_SIZE) return null;
  return Math.min(size, MAX_GROUP_SIZE);
}

export const useCampgroundFilterStore = create<CampgroundFilterState>()((set) => ({
  ...INITIAL_FILTERS,
  setSiteType: (siteType) => set({ siteType }),
  setGroupSize: (size) => set({ groupSize: clampGroupSize(size) }),
  toggleAmenity: (key) =>
    set((s) => ({
      amenities: s.amenities.includes(key) ? s.amenities.filter((k) => k !== key) : [...s.amenities, key],
    })),
  setDateWindow: (dateWindow) => set({ dateWindow }),
  clearFilters: () => set({ siteType: null, groupSize: null, amenities: [] }),
  reset: () => set({ ...INITIAL_FILTERS }),
}));

export const selectActiveFilterCount = (s: CampgroundFilterState): number =>
  (s.siteType ? 1 : 0) + (s.groupSize ? 1 : 0) + (s.amenities.length > 0 ? 1 : 0);

/** The wire filter, or undefined when nothing is active so the request omits it. */
export const selectFilterDto = (s: CampgroundFilterState): CampgroundFilterDto | undefined => {
  if (selectActiveFilterCount(s) === 0) return undefined;
  return {
    ...(s.siteType ? { site_type: s.siteType } : {}),
    ...(s.groupSize ? { group_size: s.groupSize } : {}),
    ...(s.amenities.length > 0 ? { amenities: s.amenities } : {}),
  };
};
```

- [ ] **Step 4: Write the vocabulary table and its test**

Create `frontend/src/lib/campground-vocab.ts`:

```ts
// The kinds and amenities the filter exposes, with the backend's own labels.
// Mirrors `CampsiteKind.label` and `AmenityKey.label` in the Kotlin enums; a card's
// own amenity chips still render the `label` the detail response carries.
import type { AmenityKey, CampsiteKind } from '@/api/generated/api-types';

export interface KindOption {
  value: CampsiteKind;
  label: string;
}

export interface AmenityOption {
  key: AmenityKey;
  label: string;
}

export const FILTER_SITE_TYPES: readonly KindOption[] = [
  { value: 'tent', label: 'Tent' },
  { value: 'rv', label: 'RV' },
  { value: 'cabin', label: 'Cabin' },
];

export const FILTER_AMENITIES: readonly AmenityOption[] = [
  { key: 'toilets', label: 'Toilets' },
  { key: 'showers', label: 'Showers' },
  { key: 'water', label: 'Water' },
  { key: 'pets_allowed', label: 'Pets allowed' },
];

export function kindLabel(kind: CampsiteKind): string {
  return FILTER_SITE_TYPES.find((option) => option.value === kind)?.label ?? kind;
}

export function amenityLabel(key: AmenityKey): string {
  return FILTER_AMENITIES.find((option) => option.key === key)?.label ?? key;
}
```

Create `frontend/src/lib/campground-vocab.test.ts`:

```ts
import { describe, expect, test } from 'vitest';
import { FILTER_AMENITIES, FILTER_SITE_TYPES, amenityLabel, kindLabel } from './campground-vocab';

describe('campground vocabulary', () => {
  test('exposes the three kinds and four amenities the design names', () => {
    expect(FILTER_SITE_TYPES.map((o) => o.value)).toEqual(['tent', 'rv', 'cabin']);
    expect(FILTER_AMENITIES.map((o) => o.key)).toEqual(['toilets', 'showers', 'water', 'pets_allowed']);
  });

  test('labels are the backend wording, and an unexposed key falls back to itself', () => {
    expect(kindLabel('rv')).toBe('RV');
    expect(amenityLabel('pets_allowed')).toBe('Pets allowed');
    expect(amenityLabel('wifi')).toBe('wifi');
  });
});
```

- [ ] **Step 5: Add the copy**

In `frontend/src/lib/strings.ts`, add `loading: 'Finding campgrounds in view…',` to `inViewCopy` (after `zoomIn`), and append after `inViewCopy`:

```ts
/** The campground filter block under the search row, and the cards' facet row. */
export const filterCopy = {
  pill: 'Filter campgrounds',
  pillHint: 'Then check dates',
  heading: 'Campgrounds',
  /** The filter block's count: everything in view, or how many of it pass the filter. */
  inView: (total: number) => `${total} in view`,
  matching: (matching: number, total: number) => `${matching} of ${total} in view`,
  siteType: 'Site type',
  anySiteType: 'Any',
  groupSize: 'Group size',
  fewerPeople: 'Fewer people',
  morePeople: 'More people',
  /** The card's count line with a type selected: the kind's count, then the total when they differ. */
  kindSites: (count: number, kindLabel: string, total: number) => {
    const kind = `${count} ${kindLabel.toLowerCase()} ${count === 1 ? 'site' : 'sites'}`;
    return count === total ? kind : `${kind} · ${total} total`;
  },
  /** Facet labels. */
  facetGroup: (maxPeople: number) => `Up to ${maxPeople}`,
  facetGroupNoData: 'Group size',
  noData: 'No data',
  /** The one gated step. */
  checkTitle: 'Check availability',
  checkBody: 'Add dates to see which of these have a site.',
  addDates: 'Add dates',
  startDate: 'Start date',
  endDate: 'End date',
  saveDates: 'Save',
  cancelDates: 'Cancel',
  editDates: 'Edit',
  clearDates: 'Clear',
  endBeforeStart: 'The end date must be after the start date.',
  nights: (nights: number) => (nights === 1 ? '1 night' : `${nights} nights`),
  /** "Fri Sep 11 → Sun Sep 13 · 2 nights". */
  dateSummary: (start: string, end: string, nights: number) =>
    `${start} → ${end} · ${filterCopy.nights(nights)}`,
} as const;
```

- [ ] **Step 6: Run the tests**

Run: `npm run test -- src/stores/campgroundFilterStore.test.ts src/lib/campground-vocab.test.ts && npm run typecheck`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/stores/campgroundFilterStore.ts frontend/src/stores/campgroundFilterStore.test.ts frontend/src/lib/campground-vocab.ts frontend/src/lib/campground-vocab.test.ts frontend/src/lib/strings.ts
git commit -m "feat(trip): campground filter store, exposed vocabulary and copy (#565 M2b)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: API client, query keys, boundary helper, the two hooks

**Files:**
- Create: `frontend/src/api/campground-api.ts`
- Modify: `frontend/src/queries/keys.ts`
- Modify: `frontend/src/map/viewport.ts`, `frontend/src/map/viewport.test.ts`
- Create: `frontend/src/features/trip/useCampgroundSearch.ts`, `frontend/src/features/trip/useCampgroundSearch.test.tsx`
- Create: `frontend/src/features/trip/useCampgroundSummaries.ts`, `frontend/src/features/trip/useCampgroundSummaries.test.tsx`

**Interfaces:**
- Consumes: `jsonPostOk` from `@/api/http`; `useMapStore` viewport (`bbox`, `zoom`); `CG_ZOOM_THRESHOLD`, `ViewportBbox` from `@/map/viewport`; `selectRouteActive` from `@/stores/tripStore`; `useCampgroundFilterStore`, `selectFilterDto` (Task 1); `createTestQueryClient` from `@/test/query-client`.
- Produces:
  - `api/campground-api.ts`: `type CampgroundSummary = CampgroundSummaryDto`; `searchCampgrounds(body: CampgroundSearchRequestDto, { signal }): Promise<CampgroundSearchResponseDto>`; `fetchCampgroundSummaries(ids: readonly number[], { signal }): Promise<CampgroundDetailsResponseDto>`; `CAMPGROUNDS_SEARCH_URL`, `CAMPGROUNDS_DETAILS_URL`.
  - `queryKeys.campgrounds.all()`, `.search(boundary, filter)`, `.summary(id)`, `.summaries(ids)`.
  - `bboxBoundary(bbox: ViewportBbox): BoundaryDto`.
  - `useCampgroundSearch(): CampgroundSearch` where `interface CampgroundSearch { ids: number[]; totalInBoundary: number; totalMatching: number; truncated: boolean; isFetching: boolean; /** False below the zoom gate or while a route owns the list. */ enabled: boolean }`.
  - `useCampgroundSummaries(ids: readonly number[]): CampgroundSummaries` where `interface CampgroundSummaries { byId: ReadonlyMap<number, CampgroundSummary>; isFetching: boolean }`.
  - Constants: `SUMMARY_STALE_MS = 5 * 60_000` (catalog cadence, same as `useTripCards`), `MAX_IN_VIEW_CARDS = 50` moves here from `TopBar.tsx` and is exported from `useCampgroundSummaries.ts`.

- [ ] **Step 1: Write the API client**

Create `frontend/src/api/campground-api.ts`:

```ts
import type {
  CampgroundDetailsResponseDto,
  CampgroundSearchRequestDto,
  CampgroundSearchResponseDto,
  CampgroundSummaryDto,
} from './generated/api-types';
import { jsonPostOk, type RequestOptions } from './http';

/** What the in-view list renders and filters on; the DTO, closed by codegen. */
export type CampgroundSummary = CampgroundSummaryDto;

export const CAMPGROUNDS_SEARCH_URL = '/api/campgrounds/search';
export const CAMPGROUNDS_DETAILS_URL = '/api/campgrounds/details';

export function searchCampgrounds(
  body: CampgroundSearchRequestDto,
  { signal }: RequestOptions = {},
): Promise<CampgroundSearchResponseDto> {
  return jsonPostOk<CampgroundSearchResponseDto>(CAMPGROUNDS_SEARCH_URL, body, { signal });
}

export function fetchCampgroundSummaries(
  ids: readonly number[],
  { signal }: RequestOptions = {},
): Promise<CampgroundDetailsResponseDto> {
  return jsonPostOk<CampgroundDetailsResponseDto>(CAMPGROUNDS_DETAILS_URL, { campground_ids: ids }, { signal });
}
```

- [ ] **Step 2: Add the query keys**

In `frontend/src/queries/keys.ts`, after the `campsites` block:

```ts
  campgrounds: {
    all: () => ['campgrounds'] as const,
    search: (boundary: unknown, filter: unknown) => ['campgrounds', 'search', boundary, filter ?? null] as const,
    /** One summary; what `summaries` fans a bulk response into. */
    summary: (id: number) => ['campgrounds', 'summary', id] as const,
    /** The bulk read for one id set, sorted so order does not split the cache. */
    summaries: (ids: readonly number[]) => ['campgrounds', 'summaries', [...ids].sort((a, b) => a - b)] as const,
  },
```

- [ ] **Step 3: Write the failing boundary test, then the helper**

Append to `frontend/src/map/viewport.test.ts` (add `bboxBoundary` to the import):

```ts
describe('bboxBoundary', () => {
  test('is a closed GeoJSON polygon ring around the bbox', () => {
    expect(bboxBoundary([-120.4, 38.7, -119.6, 39.4])).toEqual({
      type: 'Polygon',
      coordinates: [[[-120.4, 38.7], [-119.6, 38.7], [-119.6, 39.4], [-120.4, 39.4], [-120.4, 38.7]]],
    });
  });
});
```

Then in `frontend/src/map/viewport.ts` add:

```ts
import type { BoundaryDto } from '@/api/generated/api-types';

/** The viewport as the campground search wants it: one closed ring, west-south first. */
export function bboxBoundary([west, south, east, north]: ViewportBbox): BoundaryDto {
  return {
    type: 'Polygon',
    coordinates: [[[west, south], [east, south], [east, north], [west, north], [west, south]]],
  };
}
```

Run: `npm run test -- src/map/viewport.test.ts` → PASS.

- [ ] **Step 4: Write the failing search-hook test**

Create `frontend/src/features/trip/useCampgroundSearch.test.tsx`:

```tsx
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createTestQueryClient } from '@/test/query-client';
import { useMapStore } from '@/stores/mapStore';
import { useTripStore } from '@/stores/tripStore';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useCampgroundSearch } from './useCampgroundSearch';

const json = (body: unknown, status = 200): Response =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

const TAHOE_RING = [[[-120.4, 38.7], [-119.6, 38.7], [-119.6, 39.4], [-120.4, 39.4], [-120.4, 38.7]]];

let requests: { url: string; body: unknown }[];

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={createTestQueryClient()}>{children}</QueryClientProvider>
);

beforeEach(() => {
  requests = [];
  useMapStore.getState().reset();
  useTripStore.getState().reset();
  useCampgroundFilterStore.getState().reset();
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      requests.push({ url: String(input), body: init?.body ? JSON.parse(String(init.body)) : null });
      return json({ campground_ids: [7, 9], total_in_boundary: 17, total_matching: 9, truncated: false });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

describe('useCampgroundSearch', () => {
  test('posts the viewport as a polygon with the active filter and reports the counts', async () => {
    useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 } });
    useCampgroundFilterStore.getState().setSiteType('tent');

    const { result } = renderHook(() => useCampgroundSearch(), { wrapper });

    await waitFor(() => expect(result.current.ids).toEqual([7, 9]));
    expect(result.current).toMatchObject({ totalInBoundary: 17, totalMatching: 9, truncated: false, enabled: true });
    expect(requests[0]?.url).toBe('/api/campgrounds/search');
    expect(requests[0]?.body).toEqual({
      boundary: { type: 'Polygon', coordinates: TAHOE_RING },
      filter: { site_type: 'tent' },
    });
  });

  test('omits the filter when nothing is active', async () => {
    useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 } });

    const { result } = renderHook(() => useCampgroundSearch(), { wrapper });

    await waitFor(() => expect(result.current.ids).toHaveLength(2));
    expect(requests[0]?.body).toEqual({ boundary: { type: 'Polygon', coordinates: TAHOE_RING } });
  });

  test('does nothing below the campground zoom gate', async () => {
    useMapStore.setState({ viewport: { bbox: [-130, 30, -110, 50], zoom: 4 } });

    const { result } = renderHook(() => useCampgroundSearch(), { wrapper });

    expect(result.current.enabled).toBe(false);
    expect(result.current.ids).toEqual([]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(requests).toHaveLength(0);
  });

  test('does nothing while a route owns the list', async () => {
    useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 } });
    // Whatever `selectRouteActive` in tripStore reads; seed exactly that.
    useTripStore.setState({ route: { type: 'FeatureCollection', features: [] } as never });

    const { result } = renderHook(() => useCampgroundSearch(), { wrapper });

    expect(result.current.enabled).toBe(false);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(requests).toHaveLength(0);
  });
});
```

Read `selectRouteActive` in `frontend/src/stores/tripStore.ts` and seed the last test with exactly what makes it true (a non-empty feature if it requires one).

- [ ] **Step 5: Run it to verify it fails**

Run: `npm run test -- src/features/trip/useCampgroundSearch.test.tsx` → FAIL, module not found.

- [ ] **Step 6: Write the search hook**

Create `frontend/src/features/trip/useCampgroundSearch.ts`:

```ts
// Which campgrounds in view pass the filter: one POST per (viewport, filter).
//
// The viewport in the store is already debounced by the pin loop's publish, so
// this hook adds none. Below the campground zoom gate it asks nothing, the same
// gate the pins honour, and while a route owns the list it stays idle.
import { useQuery } from '@tanstack/react-query';
import { useShallow } from 'zustand/react/shallow';
import { searchCampgrounds } from '@/api/campground-api';
import { queryKeys } from '@/queries/keys';
import { bboxBoundary, CG_ZOOM_THRESHOLD } from '@/map/viewport';
import { selectFilterDto, useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { useMapStore } from '@/stores/mapStore';
import { selectRouteActive, useTripStore } from '@/stores/tripStore';

/** A search answer changes only when the catalog does. */
const SEARCH_STALE_MS = 60_000;

const NO_IDS: number[] = [];

export interface CampgroundSearch {
  ids: number[];
  totalInBoundary: number;
  totalMatching: number;
  truncated: boolean;
  isFetching: boolean;
  /** False below the zoom gate or while a route owns the list. */
  enabled: boolean;
}

export function useCampgroundSearch(): CampgroundSearch {
  const viewport = useMapStore((s) => s.viewport);
  const routeActive = useTripStore(selectRouteActive);
  const filter = useCampgroundFilterStore(useShallow(selectFilterDto));

  const enabled = viewport != null && viewport.zoom >= CG_ZOOM_THRESHOLD && !routeActive;
  const boundary = viewport ? bboxBoundary(viewport.bbox) : null;

  const query = useQuery({
    queryKey: queryKeys.campgrounds.search(boundary, filter),
    queryFn: ({ signal }) => searchCampgrounds({ boundary: boundary!, ...(filter ? { filter } : {}) }, { signal }),
    enabled,
    staleTime: SEARCH_STALE_MS,
    // A new viewport is a new key; keep the last answer on screen until the next lands.
    placeholderData: (previous) => previous,
  });

  return {
    ids: enabled ? (query.data?.campground_ids ?? NO_IDS) : NO_IDS,
    totalInBoundary: enabled ? (query.data?.total_in_boundary ?? 0) : 0,
    totalMatching: enabled ? (query.data?.total_matching ?? 0) : 0,
    truncated: query.data?.truncated ?? false,
    isFetching: query.isFetching,
    enabled,
  };
}
```

If the installed zustand does not export `zustand/react/shallow`, import `useShallow` from `zustand/shallow` (v4) instead.

- [ ] **Step 7: Run the search-hook test**

Run: `npm run test -- src/features/trip/useCampgroundSearch.test.tsx` → PASS.

- [ ] **Step 8: Write the failing summaries-hook test**

Create `frontend/src/features/trip/useCampgroundSummaries.test.tsx`:

```tsx
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createTestQueryClient } from '@/test/query-client';
import type { CampgroundSummary } from '@/api/campground-api';
import { useCampgroundSummaries } from './useCampgroundSummaries';

const json = (body: unknown): Response =>
  new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });

const summary = (id: number, name: string): CampgroundSummary => ({
  id,
  campground_id: id * 10,
  name,
  lng: -120,
  lat: 39,
  availability_supported: true,
  amenities: [],
  site_counts: { tent: 3 },
  site_total: 3,
});

let bodies: { campground_ids: number[] }[];
let client: QueryClient;

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={client}>{children}</QueryClientProvider>
);

beforeEach(() => {
  bodies = [];
  client = createTestQueryClient();
  vi.stubGlobal(
    'fetch',
    vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body)) as { campground_ids: number[] };
      bodies.push(body);
      return json({ campgrounds: body.campground_ids.map((id) => summary(id, `Camp ${id}`)) });
    }),
  );
});

afterEach(() => vi.unstubAllGlobals());

describe('useCampgroundSummaries', () => {
  test('fetches the ids in one request and keys the answer by id', async () => {
    const { result } = renderHook(() => useCampgroundSummaries([7, 9]), { wrapper });

    await waitFor(() => expect(result.current.byId.size).toBe(2));
    expect(result.current.byId.get(9)?.name).toBe('Camp 9');
    expect(bodies).toEqual([{ campground_ids: [7, 9] }]);
  });

  test('asks only for the ids not already cached', async () => {
    const first = renderHook(() => useCampgroundSummaries([7, 9]), { wrapper });
    await waitFor(() => expect(first.result.current.byId.size).toBe(2));

    const { result, rerender } = renderHook(({ ids }: { ids: number[] }) => useCampgroundSummaries(ids), {
      wrapper,
      initialProps: { ids: [7, 9, 11] },
    });

    await waitFor(() => expect(result.current.byId.size).toBe(3));
    expect(bodies).toEqual([{ campground_ids: [7, 9] }, { campground_ids: [11] }]);

    rerender({ ids: [9, 11] });
    await waitFor(() => expect(result.current.byId.size).toBe(2));
    expect(bodies).toHaveLength(2);
  });

  test('an empty id list asks nothing', async () => {
    const { result } = renderHook(() => useCampgroundSummaries([]), { wrapper });

    expect(result.current.byId.size).toBe(0);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(bodies).toHaveLength(0);
  });
});
```

- [ ] **Step 9: Write the summaries hook**

Create `frontend/src/features/trip/useCampgroundSummaries.ts`:

```ts
// Bulk summaries for the in-view ids, with per-id cache reuse.
//
// One request per id set, but only for the ids the cache does not hold: the
// details call replaces the two-requests-per-card pipeline M1 used, and panning
// mostly re-shows campgrounds already fetched. Each summary is also written under
// its own key so a later reader (the drawer, M3's poll) finds it without asking.
import { useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchCampgroundSummaries, type CampgroundSummary } from '@/api/campground-api';
import { queryKeys } from '@/queries/keys';

/** The catalog changes on an ETL cadence; matches `useTripCards`. */
export const SUMMARY_STALE_MS = 5 * 60_000;

/** The list is nearest-first, so a cap keeps the useful end; also the details endpoint's `max-detail-ids`. */
export const MAX_IN_VIEW_CARDS = 50;

const NO_SUMMARIES: ReadonlyMap<number, CampgroundSummary> = new Map();

export interface CampgroundSummaries {
  byId: ReadonlyMap<number, CampgroundSummary>;
  isFetching: boolean;
}

export function useCampgroundSummaries(ids: readonly number[]): CampgroundSummaries {
  const client = useQueryClient();

  const query = useQuery({
    queryKey: queryKeys.campgrounds.summaries(ids),
    queryFn: async ({ signal }) => {
      const cached = new Map<number, CampgroundSummary>();
      const missing: number[] = [];
      for (const id of ids) {
        const hit = client.getQueryData<CampgroundSummary>(queryKeys.campgrounds.summary(id));
        if (hit) cached.set(id, hit);
        else missing.push(id);
      }
      if (missing.length > 0) {
        const response = await fetchCampgroundSummaries(missing, { signal });
        for (const summary of response.campgrounds) {
          cached.set(summary.id, summary);
          client.setQueryData(queryKeys.campgrounds.summary(summary.id), summary);
        }
      }
      return cached;
    },
    enabled: ids.length > 0,
    staleTime: SUMMARY_STALE_MS,
    placeholderData: (previous) => previous,
  });

  const byId = useMemo(() => query.data ?? NO_SUMMARIES, [query.data]);
  return { byId: ids.length > 0 ? byId : NO_SUMMARIES, isFetching: query.isFetching };
}
```

- [ ] **Step 10: Run both hook tests, typecheck and lint**

Run: `npm run test -- src/features/trip/useCampgroundSearch.test.tsx src/features/trip/useCampgroundSummaries.test.tsx src/map/viewport.test.ts && npm run typecheck && npm run lint`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/api/campground-api.ts frontend/src/queries/keys.ts frontend/src/map/viewport.ts frontend/src/map/viewport.test.ts frontend/src/features/trip/useCampgroundSearch.ts frontend/src/features/trip/useCampgroundSearch.test.tsx frontend/src/features/trip/useCampgroundSummaries.ts frontend/src/features/trip/useCampgroundSummaries.test.tsx
git commit -m "feat(trip): campground search and bulk summary hooks over the M2a endpoints (#565 M2b)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Cards from summaries, count line, facets

**Files:**
- Create: `frontend/src/features/trip/campground-cards.ts`, `frontend/src/features/trip/campground-cards.test.ts`
- Create: `frontend/src/features/trip/facets.ts`, `frontend/src/features/trip/facets.test.ts`

**Interfaces:**
- Consumes: `TripCard` from `./trip-cards`; `CampgroundSummary`; `MapCenter`, `distanceKm` (`@/lib/geo`, signature `distanceKm(lat1, lon1, lat2, lon2)`); `filterCopy`, `inViewCopy`; `kindLabel`, `amenityLabel`; `CampgroundFilterState` fields `siteType`, `groupSize`, `amenities`.
- Produces:
  - `interface InViewCard extends TripCard { summary: CampgroundSummary }`.
  - `cardsFromSummaries(ids: readonly number[], byId: ReadonlyMap<number, CampgroundSummary>, center: MapCenter | null): InViewCard[]` — in `ids` order (the server already sorted nearest-first), skipping ids without a summary yet; `distKm` from `center` or 0.
  - `countLine(summary: CampgroundSummary, siteType: CampsiteKind | null): string | null` — null when `site_total === 0`.
  - `type FacetState = 'match' | 'no-data'`; `interface Facet { key: string; label: string; state: FacetState }`; `SITE_TYPE_FACET = 'site_type'`, `GROUP_SIZE_FACET = 'group_size'`; `facetsFor(summary, filter: Pick<CampgroundFilterState, 'siteType' | 'groupSize' | 'amenities'>): Facet[]` in the order site type, group size, amenities; empty when no filter is active.

- [ ] **Step 1: Write the failing tests**

Create `frontend/src/features/trip/campground-cards.test.ts`:

```ts
import { describe, expect, test } from 'vitest';
import type { CampgroundSummary } from '@/api/campground-api';
import { cardsFromSummaries, countLine } from './campground-cards';

const summary = (over: Partial<CampgroundSummary> & Pick<CampgroundSummary, 'id'>): CampgroundSummary => ({
  campground_id: over.id * 10,
  name: `Camp ${over.id}`,
  lng: -120,
  lat: 39,
  availability_supported: true,
  amenities: [],
  site_counts: { tent: 48, rv: 33 },
  site_total: 81,
  ...over,
});

describe('cardsFromSummaries', () => {
  test('keeps the server order, skips ids without a summary, and hydrates the card', () => {
    const byId = new Map([
      [2, summary({ id: 2, name: 'Nevada Beach', region: 'NV', agency: 'USDA Forest Service', rating: { average: 4.6, count: 12 } })],
      [1, summary({ id: 1, availability_supported: false })],
    ]);

    const cards = cardsFromSummaries([2, 3, 1], byId, { lng: -120, lat: 39 });

    expect(cards.map((c) => c.id)).toEqual([2, 1]);
    expect(cards[0]).toMatchObject({
      name: 'Nevada Beach',
      location: 'NV',
      agency: 'USDA Forest Service',
      rating: 4.6,
      checkable: true,
      hydrated: true,
      routeKm: null,
    });
    expect(cards[1]?.checkable).toBe(false);
    expect(cards[0]?.distKm).toBeCloseTo(0, 5);
  });
});

describe('countLine', () => {
  test('is the plain total with no type selected', () => {
    expect(countLine(summary({ id: 1 }), null)).toBe('81 sites');
  });

  test('is type-qualified with the total when they differ', () => {
    expect(countLine(summary({ id: 1 }), 'tent')).toBe('48 tent sites · 81 total');
  });

  test('drops the total when every site is of the kind', () => {
    expect(countLine(summary({ id: 1, site_counts: { tent: 81 } }), 'tent')).toBe('81 tent sites');
  });

  test('says zero of the kind when the campground has none', () => {
    expect(countLine(summary({ id: 1 }), 'cabin')).toBe('0 cabin sites · 81 total');
  });

  test('renders nothing for an empty catalog', () => {
    expect(countLine(summary({ id: 1, site_counts: {}, site_total: 0 }), 'tent')).toBeNull();
  });
});
```

Create `frontend/src/features/trip/facets.test.ts`:

```ts
import { describe, expect, test } from 'vitest';
import type { CampgroundSummary } from '@/api/campground-api';
import { facetsFor } from './facets';

const summary = (over: Partial<CampgroundSummary>): CampgroundSummary => ({
  id: 1,
  campground_id: 10,
  name: 'Camp',
  lng: -120,
  lat: 39,
  availability_supported: true,
  amenities: [],
  site_counts: { tent: 5 },
  site_total: 5,
  ...over,
});

const OFF = { siteType: null, groupSize: null, amenities: [] as const };

describe('facetsFor', () => {
  test('no active filter, no facets', () => {
    expect(facetsFor(summary({}), OFF)).toEqual([]);
  });

  test('a listed kind matches; an empty catalog is no data', () => {
    expect(facetsFor(summary({}), { ...OFF, siteType: 'tent' })).toEqual([{ key: 'site_type', label: 'Tent', state: 'match' }]);
    expect(facetsFor(summary({ site_counts: {}, site_total: 0 }), { ...OFF, siteType: 'tent' })).toEqual([
      { key: 'site_type', label: 'Tent', state: 'no-data' },
    ]);
  });

  test('group size reads the cap, or says the data is missing', () => {
    expect(facetsFor(summary({ max_people: 8 }), { ...OFF, groupSize: 4 })).toEqual([
      { key: 'group_size', label: 'Up to 8', state: 'match' },
    ]);
    expect(facetsFor(summary({}), { ...OFF, groupSize: 4 })).toEqual([
      { key: 'group_size', label: 'Group size', state: 'no-data' },
    ]);
  });

  test('an amenity uses the card label when present, the vocabulary when unknown', () => {
    const withToilets = summary({ amenities: [{ key: 'toilets', label: 'Vault toilets', present: true }] });
    expect(facetsFor(withToilets, { ...OFF, amenities: ['toilets', 'showers'] })).toEqual([
      { key: 'toilets', label: 'Vault toilets', state: 'match' },
      { key: 'showers', label: 'Showers', state: 'no-data' },
    ]);
  });

  test('facets come in filter order: type, group, amenities', () => {
    const facets = facetsFor(summary({ max_people: 6 }), { siteType: 'tent', groupSize: 2, amenities: ['water'] });
    expect(facets.map((f) => f.key)).toEqual(['site_type', 'group_size', 'water']);
  });
});
```

- [ ] **Step 2: Run them to verify they fail**

Run: `npm run test -- src/features/trip/campground-cards.test.ts src/features/trip/facets.test.ts` → FAIL, modules not found.

- [ ] **Step 3: Write `campground-cards.ts`**

```ts
// The in-view list's cards, from the bulk summaries the search hands us.
import type { CampgroundSummary } from '@/api/campground-api';
import type { CampsiteKind } from '@/api/generated/api-types';
import { distanceKm } from '@/lib/geo';
import { filterCopy, inViewCopy } from '@/lib/strings';
import { kindLabel } from '@/lib/campground-vocab';
import type { MapCenter } from '@/map/viewport';
import type { TripCard } from './trip-cards';

/** A card that carries the summary it was built from, for the count line and facets. */
export interface InViewCard extends TripCard {
  summary: CampgroundSummary;
}

/**
 * Cards in the order the search returned (nearest the view's centre first). An id
 * whose summary has not landed yet is skipped rather than shown as a placeholder:
 * the bulk read answers for the whole set at once, so there is no per-card wait.
 */
export function cardsFromSummaries(
  ids: readonly number[],
  byId: ReadonlyMap<number, CampgroundSummary>,
  center: MapCenter | null,
): InViewCard[] {
  const cards: InViewCard[] = [];
  for (const id of ids) {
    const summary = byId.get(id);
    if (!summary) continue;
    cards.push({
      id,
      name: summary.name,
      sub: '',
      location: summary.region ?? '',
      agency: summary.agency ?? '',
      lng: summary.lng,
      lat: summary.lat,
      routeKm: null,
      distKm: center ? distanceKm(center.lat, center.lng, summary.lat, summary.lng) : 0,
      checkable: summary.availability_supported,
      rating: summary.rating?.average ?? null,
      hydrated: true,
      summary,
    });
  }
  return cards;
}

/** The card's count line; null for an empty catalog. */
export function countLine(summary: CampgroundSummary, siteType: CampsiteKind | null): string | null {
  if (summary.site_total === 0) return null;
  if (!siteType) return inViewCopy.sites(summary.site_total);
  return filterCopy.kindSites(summary.site_counts[siteType] ?? 0, kindLabel(siteType), summary.site_total);
}
```

- [ ] **Step 4: Write `facets.ts`**

```ts
// One facet per active filter. A card in the list has already passed the server's
// filter, so a facet is either a match or "no data": the provider has no field for
// it, which must read differently from a miss.
import type { CampgroundSummary } from '@/api/campground-api';
import { amenityLabel, kindLabel } from '@/lib/campground-vocab';
import { filterCopy } from '@/lib/strings';
import type { CampgroundFilterState } from '@/stores/campgroundFilterStore';

export type FacetState = 'match' | 'no-data';

export interface Facet {
  key: string;
  label: string;
  state: FacetState;
}

export const SITE_TYPE_FACET = 'site_type';
export const GROUP_SIZE_FACET = 'group_size';

type ActiveFilter = Pick<CampgroundFilterState, 'siteType' | 'groupSize' | 'amenities'>;

export function facetsFor(summary: CampgroundSummary, filter: ActiveFilter): Facet[] {
  const facets: Facet[] = [];
  if (filter.siteType) {
    facets.push({
      key: SITE_TYPE_FACET,
      label: kindLabel(filter.siteType),
      state: summary.site_total > 0 ? 'match' : 'no-data',
    });
  }
  if (filter.groupSize) {
    facets.push(
      summary.max_people != null
        ? { key: GROUP_SIZE_FACET, label: filterCopy.facetGroup(summary.max_people), state: 'match' }
        : { key: GROUP_SIZE_FACET, label: filterCopy.facetGroupNoData, state: 'no-data' },
    );
  }
  for (const key of filter.amenities) {
    const listed = summary.amenities.find((amenity) => amenity.key === key);
    facets.push(
      listed
        ? { key, label: listed.label, state: 'match' }
        : { key, label: amenityLabel(key), state: 'no-data' },
    );
  }
  return facets;
}
```

- [ ] **Step 5: Run the tests**

Run: `npm run test -- src/features/trip/campground-cards.test.ts src/features/trip/facets.test.ts && npm run typecheck` → PASS.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/trip/campground-cards.ts frontend/src/features/trip/campground-cards.test.ts frontend/src/features/trip/facets.ts frontend/src/features/trip/facets.test.ts
git commit -m "feat(trip): in-view cards from campground summaries, with facets and a typed count line (#565 M2b)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: The controls: stepper, date fields, filter panel

**Files:**
- Create: `frontend/src/features/trip/GroupSizeStepper.tsx`, `frontend/src/features/trip/GroupSizeStepper.stories.tsx`
- Create: `frontend/src/features/trip/DateWindowFields.tsx`
- Create: `frontend/src/features/trip/FilterPanel.tsx`, `frontend/src/features/trip/FilterPanel.stories.tsx`, `frontend/src/features/trip/FilterPanel.test.tsx`
- Modify: `frontend/src/features/trip/topbar.css` (append)

**Interfaces:**
- Consumes: `SegmentedControl`, `Chip`, `Banner`, `Button`, `Icon`, `SeededTextField` from `@ui` (sprite icons available: `filter`, `people`, `calendar`, `check`, `help`, `minus`, `add`); the store and selectors (Task 1); `FILTER_SITE_TYPES`, `FILTER_AMENITIES`; `filterCopy`.
- Produces:
  - `GroupSizeStepper({ value: number | null; onChange: (next: number | null) => void })`: "Fewer people" / "More people" icon buttons around the count (an em dash when off). Plus from off → `MIN_GROUP_SIZE`; minus at `MIN_GROUP_SIZE` → null; plus at `MAX_GROUP_SIZE` disabled.
  - `DateWindowFields({ initial: DateWindow | null; onSave: (window: DateWindow) => void; onCancel: () => void })`; exports `nightsBetween(start, end): number` and `formatDateShort(iso): string` ("Fri Sep 11").
  - `FilterPanel({ open: boolean; onToggle: () => void; totalInBoundary: number; totalMatching: number })`: the pill row (always), and when `open` the controls block, then the Check availability banner, the date fields, or the saved-dates summary.

- [ ] **Step 1: Write the failing panel test**

Create `frontend/src/features/trip/FilterPanel.test.tsx`:

```tsx
import { beforeEach, describe, expect, test } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { FilterPanel } from './FilterPanel';

const filters = () => useCampgroundFilterStore.getState();

beforeEach(() => filters().reset());

const mount = (open = true) =>
  render(<FilterPanel open={open} onToggle={() => {}} totalInBoundary={17} totalMatching={17} />);

describe('FilterPanel', () => {
  test('closed, it is the pill with its hint', () => {
    mount(false);

    expect(screen.getByRole('button', { name: /Filter campgrounds/ })).toBeInTheDocument();
    expect(screen.getByText('Then check dates')).toBeInTheDocument();
    expect(screen.queryByRole('radiogroup', { name: 'Site type' })).toBeNull();
  });

  test('open, the site type control writes the store', () => {
    mount();

    fireEvent.click(screen.getByRole('radio', { name: 'Tent' }));

    expect(filters().siteType).toBe('tent');
  });

  test('the stepper turns the group filter on at the minimum and off again below it', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'More people' }));
    expect(filters().groupSize).toBe(2);

    fireEvent.click(screen.getByRole('button', { name: 'More people' }));
    expect(filters().groupSize).toBe(3);

    fireEvent.click(screen.getByRole('button', { name: 'Fewer people' }));
    fireEvent.click(screen.getByRole('button', { name: 'Fewer people' }));
    expect(filters().groupSize).toBeNull();
  });

  test('amenity chips toggle', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Toilets' }));
    expect(filters().amenities).toEqual(['toilets']);

    fireEvent.click(screen.getByRole('button', { name: 'Toilets' }));
    expect(filters().amenities).toEqual([]);
  });

  test('the head reports matches against the view once a filter is active', () => {
    filters().setSiteType('tent');
    render(<FilterPanel open onToggle={() => {}} totalInBoundary={17} totalMatching={9} />);

    expect(screen.getByText('9 of 17 in view')).toBeInTheDocument();
  });

  test('Add dates reveals the fields and Save stores the window only', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    fireEvent.change(screen.getByLabelText('Start date'), { target: { value: '2026-09-11' } });
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2026-09-13' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(filters().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
    expect(screen.getByText('Fri Sep 11 → Sun Sep 13 · 2 nights')).toBeInTheDocument();
  });

  test('an end date before the start disables Save and explains', () => {
    mount();

    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    fireEvent.change(screen.getByLabelText('Start date'), { target: { value: '2026-09-13' } });
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2026-09-11' } });

    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.getByText('The end date must be after the start date.')).toBeInTheDocument();
  });
});
```

Verify in `node_modules/@lew-ds/lds/src/templates/segmented-control.js` what LDS renders: if the options are `role="radio"` elements whose accessible name is the label, the `getByRole('radio', { name })` queries hold; otherwise adjust to what the template renders (e.g. `getByRole('button', { name: 'Tent' })`) and note it in the report. Same for `Chip` with `onClick`: if it is not a `<button>`, query by `getByText` and click that.

- [ ] **Step 2: Run it to verify it fails**

Run: `npm run test -- src/features/trip/FilterPanel.test.tsx` → FAIL, module not found.

- [ ] **Step 3: Write `GroupSizeStepper.tsx`**

```tsx
// The group-size stepper. LDS has no stepper primitive, so this is two icon
// buttons around the count; below the minimum the filter is simply off.
import { Button, Icon } from '@ui';
import { filterCopy } from '@/lib/strings';
import { MAX_GROUP_SIZE, MIN_GROUP_SIZE } from '@/stores/campgroundFilterStore';

const OFF_GLYPH = '—';

export interface GroupSizeStepperProps {
  value: number | null;
  onChange: (next: number | null) => void;
}

export function GroupSizeStepper({ value, onChange }: GroupSizeStepperProps) {
  const decrement = () => onChange(value == null || value <= MIN_GROUP_SIZE ? null : value - 1);
  const increment = () => onChange(value == null ? MIN_GROUP_SIZE : Math.min(value + 1, MAX_GROUP_SIZE));
  return (
    <div className="tb-stepper" role="group" aria-label={filterCopy.groupSize}>
      <Button variant="tertiary" size="sm" iconOnly aria-label={filterCopy.fewerPeople} disabled={value == null} onClick={decrement}>
        <Icon name="minus" aria-hidden="true" />
      </Button>
      <span className="tb-stepper-value" aria-live="polite">
        {value ?? OFF_GLYPH}
      </span>
      <Button variant="tertiary" size="sm" iconOnly aria-label={filterCopy.morePeople} disabled={value === MAX_GROUP_SIZE} onClick={increment}>
        <Icon name="add" aria-hidden="true" />
      </Button>
    </div>
  );
}
```

If `aria-label` does not reach LDS `Button`'s `<button>` (LDS spreads unknown `HtmlProps`, so it should), fall back to the topbar's existing plain `<button className="tb-icon-btn">` and say so in the report.

- [ ] **Step 4: Write `DateWindowFields.tsx`**

```tsx
// Start and end date entry for the Check availability step. M2 stores the window
// only; M3 polls with it. The fields are conditionally rendered, so they seed
// through `SeededTextField` and mirror into local state for the Save payload.
import { useState } from 'react';
import { Button, SeededTextField } from '@ui';
import { filterCopy } from '@/lib/strings';
import type { DateWindow } from '@/stores/campgroundFilterStore';

const MS_PER_DAY = 86_400_000;
const START_ID = 'tb-dates-start';
const END_ID = 'tb-dates-end';

const shortDate = new Intl.DateTimeFormat('en-US', { weekday: 'short', month: 'short', day: 'numeric', timeZone: 'UTC' });

/** Whole nights between two ISO dates; 0 or less means the window is not valid. */
export function nightsBetween(start: string, end: string): number {
  return Math.round((Date.parse(end) - Date.parse(start)) / MS_PER_DAY);
}

/** "Fri Sep 11" from "2026-09-11"; UTC so the calendar date never shifts. */
export function formatDateShort(iso: string): string {
  return shortDate.format(new Date(`${iso}T00:00:00Z`));
}

const valueOf = (event: Event): string => (event.target as HTMLInputElement).value;

export interface DateWindowFieldsProps {
  initial: DateWindow | null;
  onSave: (window: DateWindow) => void;
  onCancel: () => void;
}

export function DateWindowFields({ initial, onSave, onCancel }: DateWindowFieldsProps) {
  const [start, setStart] = useState(initial?.start ?? '');
  const [end, setEnd] = useState(initial?.end ?? '');
  const filled = start !== '' && end !== '';
  const inverted = filled && nightsBetween(start, end) <= 0;
  return (
    <div className="tb-dates">
      <div className="tb-dates-fields">
        <SeededTextField id={START_ID} name="start_date" type="date" label={filterCopy.startDate} seed={start} onChange={(e) => setStart(valueOf(e))} />
        <SeededTextField id={END_ID} name="end_date" type="date" label={filterCopy.endDate} seed={end} onChange={(e) => setEnd(valueOf(e))} />
      </div>
      {inverted ? <span className="tb-dates-error">{filterCopy.endBeforeStart}</span> : null}
      <div className="tb-dates-actions">
        <Button variant="tertiary" size="sm" onClick={onCancel}>
          {filterCopy.cancelDates}
        </Button>
        <Button variant="primary" size="sm" disabled={!filled || inverted} onClick={() => onSave({ start, end })}>
          {filterCopy.saveDates}
        </Button>
      </div>
    </div>
  );
}
```

- [ ] **Step 5: Write `FilterPanel.tsx`**

```tsx
// The filter block under the search row: the pill, the three local filters, and
// the one gated step (dates). Filter state lives in the store; only "is the block
// open" and "are the date fields showing" are local.
import { useState } from 'react';
import { Banner, Button, Chip, Icon, SegmentedControl } from '@ui';
import type { CampsiteKind } from '@/api/generated/api-types';
import { FILTER_AMENITIES, FILTER_SITE_TYPES } from '@/lib/campground-vocab';
import { filterCopy } from '@/lib/strings';
import { selectActiveFilterCount, useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { DateWindowFields, formatDateShort, nightsBetween } from './DateWindowFields';
import { GroupSizeStepper } from './GroupSizeStepper';

const ANY_VALUE = 'any';
const SITE_TYPE_OPTIONS = [
  { value: ANY_VALUE, label: filterCopy.anySiteType },
  ...FILTER_SITE_TYPES.map((option) => ({ value: option.value, label: option.label })),
];

export interface FilterPanelProps {
  open: boolean;
  onToggle: () => void;
  totalInBoundary: number;
  totalMatching: number;
}

export function FilterPanel({ open, onToggle, totalInBoundary, totalMatching }: FilterPanelProps) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const groupSize = useCampgroundFilterStore((s) => s.groupSize);
  const amenities = useCampgroundFilterStore((s) => s.amenities);
  const dateWindow = useCampgroundFilterStore((s) => s.dateWindow);
  const activeCount = useCampgroundFilterStore(selectActiveFilterCount);
  const setSiteType = useCampgroundFilterStore((s) => s.setSiteType);
  const setGroupSize = useCampgroundFilterStore((s) => s.setGroupSize);
  const toggleAmenity = useCampgroundFilterStore((s) => s.toggleAmenity);
  const setDateWindow = useCampgroundFilterStore((s) => s.setDateWindow);
  const [editingDates, setEditingDates] = useState(false);

  const count = activeCount > 0 ? filterCopy.matching(totalMatching, totalInBoundary) : filterCopy.inView(totalInBoundary);

  return (
    <div className="tb-filters" id="tb-filters">
      <div className="tb-filters-pill-row">
        <Button variant="secondary" size="sm" aria-expanded={open} aria-controls="tb-filters-body" onClick={onToggle}>
          <Icon name="filter" aria-hidden="true" />
          {filterCopy.pill}
          {activeCount > 0 ? <span className="tb-filters-badge">{activeCount}</span> : null}
        </Button>
        <span className="tb-filters-hint">{filterCopy.pillHint}</span>
      </div>

      {open ? (
        <div className="tb-filters-body" id="tb-filters-body">
          <div className="tb-filters-head">
            <span className="tb-filters-heading">{filterCopy.heading}</span>
            <span className="tb-filters-count">{count}</span>
          </div>
          <div className="tb-filters-row">
            <SegmentedControl
              size="sm"
              name="site_type"
              label={filterCopy.siteType}
              options={SITE_TYPE_OPTIONS}
              value={siteType ?? ANY_VALUE}
              onChange={(next) => setSiteType(next === ANY_VALUE ? null : (next as CampsiteKind))}
            />
            <GroupSizeStepper value={groupSize} onChange={setGroupSize} />
          </div>
          <div className="tb-filters-row tb-filters-chips">
            {FILTER_AMENITIES.map((option) => (
              <Chip key={option.key} size="sm" selected={amenities.includes(option.key)} onClick={() => toggleAmenity(option.key)}>
                {option.label}
              </Chip>
            ))}
          </div>

          {editingDates ? (
            <DateWindowFields
              initial={dateWindow}
              onSave={(next) => {
                setDateWindow(next);
                setEditingDates(false);
              }}
              onCancel={() => setEditingDates(false)}
            />
          ) : dateWindow ? (
            <div className="tb-dates-summary">
              <Icon name="calendar" aria-hidden="true" />
              <span>{filterCopy.dateSummary(formatDateShort(dateWindow.start), formatDateShort(dateWindow.end), nightsBetween(dateWindow.start, dateWindow.end))}</span>
              <Button variant="tertiary" size="sm" onClick={() => setEditingDates(true)}>
                {filterCopy.editDates}
              </Button>
              <Button variant="tertiary" size="sm" onClick={() => setDateWindow(null)}>
                {filterCopy.clearDates}
              </Button>
            </div>
          ) : (
            <Banner
              icon={<Icon name="calendar" aria-hidden="true" />}
              title={filterCopy.checkTitle}
              actions={
                <Button variant="primary" size="sm" onClick={() => setEditingDates(true)}>
                  {filterCopy.addDates}
                </Button>
              }
            >
              {filterCopy.checkBody}
            </Banner>
          )}
        </div>
      ) : null}
    </div>
  );
}
```

- [ ] **Step 6: Append the CSS**

Append to `frontend/src/features/trip/topbar.css`:

```css
/* Campground filters (M2): the pill row, the controls block, the dates step. */
.tb-filters {
  padding: 0 6px 6px;
}

.tb-filters-pill-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tb-filters-hint {
  font-size: 11px;
  color: var(--rt-muted);
}

.tb-filters-badge {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--rt-brand-tint);
  color: var(--rt-brand-text);
  font-size: 11px;
}

.tb-filters-body {
  margin-top: 8px;
  padding: 8px;
  border: 1px solid var(--rt-border);
  border-radius: 8px;
  background: var(--rt-surface);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tb-filters-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 11px;
}

.tb-filters-heading {
  font-weight: 600;
  color: var(--rt-text);
}

.tb-filters-count {
  color: var(--rt-muted);
}

.tb-filters-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tb-filters-chips {
  flex-wrap: wrap;
}

.tb-stepper {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.tb-stepper-value {
  min-width: 2ch;
  text-align: center;
  font-variant-numeric: tabular-nums;
  color: var(--rt-text);
}

.tb-dates {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.tb-dates-fields {
  display: flex;
  gap: 8px;
}

.tb-dates-error {
  font-size: 11px;
  color: var(--rt-danger);
}

.tb-dates-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.tb-dates-summary {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--rt-text);
}

/* The card's facet row: one entry per active filter, match or no data. */
.tb-facets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 2px;
  font-size: 11px;
}

.tb-facet {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--rt-text);
}

.tb-facet--no-data {
  color: var(--rt-faint);
}
```

Every token above must exist: run `node scripts/check-token-usage.mjs` from `frontend/`; if `--rt-brand-tint`, `--rt-brand-text` or `--rt-danger` is not defined under `src/tokens/`, grep that directory for `brand` and `danger`/`error`, use the nearest existing role, and note it in the report.

- [ ] **Step 7: Stories**

Create `frontend/src/features/trip/GroupSizeStepper.stories.tsx`:

```tsx
import { useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { GroupSizeStepper } from './GroupSizeStepper';
import './topbar.css';

const meta = {
  title: 'Trip/GroupSizeStepper',
  parameters: {
    docs: { description: { component: 'Group size for the campground filter. Off until the first step; below the minimum it turns off again.' } },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

function Live({ initial }: { initial: number | null }) {
  const [value, setValue] = useState<number | null>(initial);
  return <GroupSizeStepper value={value} onChange={setValue} />;
}

export const Off: Story = { render: () => <Live initial={null} /> };
export const FourPeople: Story = { render: () => <Live initial={4} /> };
```

Create `frontend/src/features/trip/FilterPanel.stories.tsx`:

```tsx
import { useEffect, useState } from 'react';
import type { Meta, StoryObj } from '@storybook/react-vite';
import { useCampgroundFilterStore } from '@/stores/campgroundFilterStore';
import { FilterPanel } from './FilterPanel';
import './topbar.css';

const PANEL_WIDTH = 420;

function Panel({ seed, open: initialOpen = true }: { seed?: () => void; open?: boolean }) {
  const [open, setOpen] = useState(initialOpen);
  useEffect(() => {
    useCampgroundFilterStore.getState().reset();
    seed?.();
    return () => useCampgroundFilterStore.getState().reset();
  }, [seed]);
  return (
    <div className="tb-panel" style={{ position: 'relative', inset: 'auto', width: PANEL_WIDTH }}>
      <FilterPanel open={open} onToggle={() => setOpen((o) => !o)} totalInBoundary={17} totalMatching={13} />
    </div>
  );
}

const meta = {
  title: 'Trip/FilterPanel',
  parameters: {
    docs: {
      description: {
        component:
          'The filter block under the search row: the pill, site type, group size, amenities, and the ' +
          'Check availability banner that is the only gated step.',
      },
    },
  },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

export const Closed: Story = { render: () => <Panel open={false} /> };
export const Open: Story = { render: () => <Panel /> };
export const Filtered: Story = {
  render: () => (
    <Panel
      seed={() => {
        const s = useCampgroundFilterStore.getState();
        s.setSiteType('tent');
        s.setGroupSize(4);
        s.toggleAmenity('toilets');
      }}
    />
  ),
};
export const WithDates: Story = {
  render: () => (
    <Panel seed={() => useCampgroundFilterStore.getState().setDateWindow({ start: '2026-09-11', end: '2026-09-13' })} />
  ),
};
```

- [ ] **Step 8: Run the tests, typecheck, lint, CSS guards**

Run: `npm run test -- src/features/trip/FilterPanel.test.tsx && npm run typecheck && npm run lint && node scripts/check-css-blocks.mjs && node scripts/check-token-usage.mjs`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/features/trip/GroupSizeStepper.tsx frontend/src/features/trip/GroupSizeStepper.stories.tsx frontend/src/features/trip/DateWindowFields.tsx frontend/src/features/trip/FilterPanel.tsx frontend/src/features/trip/FilterPanel.stories.tsx frontend/src/features/trip/FilterPanel.test.tsx frontend/src/features/trip/topbar.css
git commit -m "feat(trip): the campground filter panel, stepper and Add dates step (#565 M2b)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Wire the list: TripResults on summaries, TopBar on the hooks, retire the per-card pipeline

**Files:**
- Modify: `frontend/src/features/trip/TripResults.tsx`, `frontend/src/features/trip/TripResults.stories.tsx`
- Modify: `frontend/src/features/trip/TopBar.tsx`, `frontend/src/features/trip/TopBar.test.tsx`
- Modify: `frontend/src/features/trip/trip-cards.ts`, `frontend/src/features/trip/trip-cards.test.ts` (remove `viewportCardsFromFeatures` and its tests)
- Delete: `frontend/src/features/trip/useSiteCounts.ts`, `frontend/src/features/trip/site-counts.ts`, `frontend/src/features/trip/site-counts.test.ts`
- Modify: `frontend/src/features/map/MapView.test.tsx` only if it asserts on the in-view list's per-card requests (grep `poiRequests` / `campsites`); keep its assertions true.

**Interfaces:**
- Consumes: `InViewCard`, `cardsFromSummaries`, `countLine`, `facetsFor` (Task 3); `useCampgroundSearch`, `useCampgroundSummaries`, `MAX_IN_VIEW_CARDS` (Task 2); `FilterPanel` (Task 4); `bboxCenter`, `CG_ZOOM_THRESHOLD`; store selectors; `inViewCopy.loading` (Task 1).
- Produces: `TripResultsProps` viewport variant becomes
  ```ts
  interface ViewportProps extends CommonProps {
    variant: 'viewport';
    cards: readonly InViewCard[];
    /** False below the zoom gate, where nothing is asked. */
    campgroundsRequested: boolean;
    /** True while the search or the summaries are in flight and the list is empty. */
    loading: boolean;
    totalInBoundary: number;
    totalMatching: number;
    truncated: boolean;
  }
  ```
  The route variant is unchanged.

- [ ] **Step 1: Update `TripResults.tsx`**

Replace the viewport variant's props with the block above (`CommonProps` keeps `cards: readonly TripCard[]`; the viewport variant narrows to `readonly InViewCard[]`). Remove the `SiteCountsById` import and the `siteCounts` prop.

`countLine` for the viewport variant becomes:

```ts
  if (props.variant === 'viewport') {
    const denominator = props.totalMatching;
    const count = visible.length === denominator ? String(denominator) : `${visible.length} of ${denominator}`;
    if (visible.length === 0) return `· ${count}`;
    const checkable = visible.filter((card) => card.checkable).length;
    return `· ${count} · ${inViewCopy.checkableCount(checkable)}`;
  }
```

(all viewport cards are hydrated now, so the "wait for every card" guard goes). `emptyCopy`'s viewport branch: `if (!props.campgroundsRequested) return inViewCopy.zoomIn; if (props.loading) return inViewCopy.loading; if (total === 0) return inViewCopy.none;`.

`ViewportMeta` and a new `FacetRow`:

```tsx
function ViewportMeta({ card }: { card: InViewCard }) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const line = countLine(card.summary, siteType);
  return (
    <>
      {line ? <span>{line}</span> : null}
      {card.rating != null ? <span>{`${RATING_PREFIX} ${card.rating.toFixed(1)}`}</span> : null}
      {!card.checkable ? <span>{inViewCopy.notCheckable}</span> : null}
    </>
  );
}

function FacetRow({ card }: { card: InViewCard }) {
  const siteType = useCampgroundFilterStore((s) => s.siteType);
  const groupSize = useCampgroundFilterStore((s) => s.groupSize);
  const amenities = useCampgroundFilterStore((s) => s.amenities);
  const facets = facetsFor(card.summary, { siteType, groupSize, amenities });
  if (facets.length === 0) return null;
  return (
    <span className="tb-facets">
      {facets.map((facet) => (
        <span
          key={facet.key}
          className={facet.state === 'no-data' ? 'tb-facet tb-facet--no-data' : 'tb-facet'}
          title={facet.state === 'no-data' ? filterCopy.noData : undefined}
        >
          <Icon name={facet.state === 'match' ? 'check' : 'help'} aria-hidden="true" />
          {facet.label}
        </span>
      ))}
    </span>
  );
}
```

Render `<FacetRow card={card} />` inside the card body after `.tb-card-meta` for the viewport variant only (narrow on `props.variant === 'viewport'` and cast the card to `InViewCard` there, or map over `props.cards` inside the branch so the type is already narrow).

- [ ] **Step 2: Update `TopBar.tsx`**

Remove the imports of `viewportCardsFromFeatures`, `useSiteCounts`, the local `MAX_IN_VIEW_CARDS` and `NO_CARDS` constants, and the `viewportCampgrounds` store read. Add:

```ts
import { FilterPanel } from './FilterPanel';
import { useCampgroundSearch } from './useCampgroundSearch';
import { MAX_IN_VIEW_CARDS, useCampgroundSummaries } from './useCampgroundSummaries';
import { cardsFromSummaries } from './campground-cards';
```

Replace the in-view block with:

```ts
  const [filtersOpen, setFiltersOpen] = useState(false);
  const search = useCampgroundSearch();
  const inViewIds = useMemo(() => search.ids.slice(0, MAX_IN_VIEW_CARDS), [search.ids]);
  const summaries = useCampgroundSummaries(inViewIds);
  const inViewCards = useMemo(
    () => cardsFromSummaries(inViewIds, summaries.byId, viewportBbox ? bboxCenter(viewportBbox) : null),
    [inViewIds, summaries.byId, viewportBbox],
  );
```

keeping the `viewportBbox`, `campgroundsRequested` and `zoomAllowsCampgrounds` reads. Mount `<FilterPanel open={filtersOpen} onToggle={() => setFiltersOpen((o) => !o)} totalInBoundary={search.totalInBoundary} totalMatching={search.totalMatching} />` directly under the `tb-actions` row, only when `!showRoute`. The viewport `TripResults` call becomes:

```tsx
        <TripResults
          variant="viewport"
          cards={inViewCards}
          campgroundsRequested={campgroundsRequested && zoomAllowsCampgrounds}
          loading={inViewCards.length === 0 && (search.isFetching || summaries.isFetching)}
          totalInBoundary={search.totalInBoundary}
          totalMatching={search.totalMatching}
          truncated={search.truncated}
        />
```

- [ ] **Step 3: Retire the per-card pipeline**

Delete `useSiteCounts.ts`, `site-counts.ts`, `site-counts.test.ts`. In `trip-cards.ts` remove `viewportCardsFromFeatures` and, if nothing else uses it, the `MapCenter` import; in `trip-cards.test.ts` remove its describe block. Grep `src` for any remaining importer (`useSiteCounts`, `siteCountsOf`, `viewportCardsFromFeatures`, `SiteCountsById`); `TripResults.stories.tsx` is one and is fixed in Step 5.

- [ ] **Step 4: Update `TopBar.test.tsx`**

Change the fetch stub's signature to `(input: RequestInfo | URL, init?: RequestInit)` and add, before the 404 fallthrough:

```ts
      if (url.startsWith('/api/campgrounds/search')) return json(campgroundSearch);
      if (url.startsWith('/api/campgrounds/details')) {
        const body = JSON.parse(String(init?.body)) as { campground_ids: number[] };
        return json({ campgrounds: body.campground_ids.map((id) => campgroundSummaries[id]).filter(Boolean) });
      }
```

with `let campgroundSearch: CampgroundSearchResponseDto; let campgroundSummaries: Record<number, CampgroundSummary>;` seeded in `beforeEach` to two summaries (id 21 "Fallen Leaf", `site_counts: { tent: 132, rv: 74 }`, `site_total: 206`, `availability_supported: true`; id 22 "Zephyr Cove", `site_counts: { rv: 110, tent: 60 }`, `site_total: 170`, `availability_supported: false`) and `{ campground_ids: [21, 22], total_in_boundary: 2, total_matching: 2, truncated: false }`. Add `useCampgroundFilterStore.getState().reset()` to `beforeEach`. Import `fireEvent` from Testing Library.

Rewrite the M1 in-view `describe` (the one that seeded `viewportCampgrounds` and stubbed `/api/pois/{id}` and `/campsites`) so it seeds `useMapStore.setState({ viewport: { bbox: [-120.4, 38.7, -119.6, 39.4], zoom: 9 }, campgroundsRequested: true })` and asserts:

```ts
  test('lists the campgrounds in view from the bulk summaries', async () => {
    mount();

    await waitFor(() => expect(screen.getByText('Fallen Leaf')).toBeInTheDocument());
    expect(screen.getByText('Zephyr Cove')).toBeInTheDocument();
    expect(screen.getByText('· 2 · 1 checkable online')).toBeInTheDocument();
    expect(screen.getByText('Not checkable online')).toBeInTheDocument();
    expect(urls.filter((u) => u.startsWith('/api/campgrounds/details'))).toHaveLength(1);
    expect(urls.filter((u) => u.startsWith('/api/pois/'))).toHaveLength(0);
  });

  test('a site type filter re-searches with the filter and type-qualifies the count line', async () => {
    mount();
    await waitFor(() => expect(screen.getByText('Fallen Leaf')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Filter campgrounds/ }));
    fireEvent.click(screen.getByRole('radio', { name: 'Tent' }));

    await waitFor(() => expect(screen.getByText('132 tent sites · 206 total')).toBeInTheDocument());
    expect(urls.filter((u) => u.startsWith('/api/campgrounds/search'))).toHaveLength(2);
  });

  test('below the zoom gate nothing is asked and the hint shows', () => {
    useMapStore.setState({ viewport: { bbox: [-130, 30, -110, 50], zoom: 4 }, campgroundsRequested: false });
    mount();

    expect(screen.getByText('Zoom in to load campgrounds.')).toBeInTheDocument();
    expect(urls.filter((u) => u.startsWith('/api/campgrounds'))).toHaveLength(0);
  });

  test('Add dates stores the window and triggers no provider call', async () => {
    mount();
    await waitFor(() => expect(screen.getByText('Fallen Leaf')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Filter campgrounds/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Add dates' }));
    fireEvent.change(screen.getByLabelText('Start date'), { target: { value: '2026-09-11' } });
    fireEvent.change(screen.getByLabelText('End date'), { target: { value: '2026-09-13' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(useCampgroundFilterStore.getState().dateWindow).toEqual({ start: '2026-09-11', end: '2026-09-13' });
    expect(urls.filter((u) => u.includes('availability'))).toHaveLength(0);
  });

  test('a capped list reports the rendered count against the matches', async () => {
    campgroundSearch = { campground_ids: [21, 22], total_in_boundary: 80, total_matching: 60, truncated: true };
    mount();

    await waitFor(() => expect(screen.getByText('· 2 of 60 · 1 checkable online')).toBeInTheDocument());
  });
```

Keep every existing route-mode assertion (`Standard campground`, `· 2`, `· 1 of 2`) unchanged. Use the same `radio` / `button` role for the Tent segment that Task 4's report settled on.

- [ ] **Step 5: Update `TripResults.stories.tsx`**

Replace the `SiteCountsById` import and `NO_SITE_COUNTS` / `SITE_COUNTS` with in-view cards built through `cardsFromSummaries` from three `CampgroundSummary` fixtures: Fallen Leaf (206 sites, `{ tent: 132, rv: 74 }`, `max_people: 8`, amenities toilets and showers present, rating 4.7); D. L. Bliss (165, `{ tent: 165 }`, `max_people: 8`, agency "California State Parks", rating 4.8); Zephyr Cove (170, `{ rv: 110, tent: 60 }`, `availability_supported: false`, no `max_people`, region "NV", agency "Private"). Update the viewport stories to the new props (`totalInBoundary`, `totalMatching`, `truncated`, `loading`), and add `InViewFiltered`, whose `Panel` seeds the filter store with `setSiteType('tent'); setGroupSize(4); toggleAmenity('toilets')` so the facet rows render (and resets it on unmount).

- [ ] **Step 6: Run the whole frontend gate**

Run: `npm run test && npm run typecheck && npm run lint && node scripts/check-css-blocks.mjs && node scripts/check-token-usage.mjs && npm run build && npm run build-storybook`
Expected: all PASS; the build's pre-existing chunk-size warning is fine.

- [ ] **Step 7: Commit**

```bash
git add -A frontend/src/features/trip frontend/src/lib/strings.ts
git commit -m "feat(trip): the in-view list runs on campground search and summaries, with filters and facets (#565 M2b)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Browser verification

**Files:** none.

- [ ] **Step 1: Start the stack**

Docker up; `docker start roadtrip-postgres-1` if it is `Exited` and wait for `docker exec roadtrip-postgres-1 pg_isready`. From the repo root, in the background: `ROADTRIP_PROFILE=local ./secrets/manage.py exec local -- ./gradlew :backend:run --console=plain --offline` (port 8765; master has the M2a routes). From `frontend/`, in the background: `npm run dev -- --port 5174 --strictPort`. Wait for `curl -sf http://127.0.0.1:8765/api/me`.

- [ ] **Step 2: Drive the page**

Open `http://localhost:5174`. Search "Lake Tahoe" so the map zooms past the campground gate. Confirm: the list head reads "Campgrounds in view · N · M checkable online"; the Filter campgrounds pill with "Then check dates" sits under the search row; opening it shows "Campgrounds" and "N in view". Pick Tent: the head becomes "K of N in view", cards show "x tent sites · y total" and a Tent facet with a check. Step group size to 4: cards show "Up to N" or a faint "Group size" facet. Toggle Toilets. In the Network panel, one `search` per change and `details` only for ids not yet fetched. Click Add dates, enter a window, Save: the summary line appears, and no request to any availability URL is made. Record what you see in the ledger.

- [ ] **Step 3: Stop both servers.**

---

## Self-review notes

- Spec coverage: store (T1), fetching with per-id cache and nearest-first cap (T2), cards from summaries + type-qualified count line (T3), facets match / no-data (T3, T5), heads "N in view" / "K of N in view" and the M1 list head (T4, T5), controls incl. stepper clamp and Add dates storing only the window (T4), route list untouched and desktop only (T5), retiring the M1 pipeline (T5), labels from the backend vocabulary (T1, T3).
- Type consistency: `InViewCard`, `cardsFromSummaries`, `countLine`, `facetsFor`, `useCampgroundSearch`, `useCampgroundSummaries`, `MAX_IN_VIEW_CARDS`, `FilterPanel` props, `DateWindow`, `MIN_GROUP_SIZE`/`MAX_GROUP_SIZE`, `inViewCopy.loading` are each defined once and used by those names.
- Known judgment calls: `MAX_IN_VIEW_CARDS` equals the backend's `max-detail-ids` default (50) and must not exceed it; `useCampgroundSearch` adds no debounce because the store's viewport is already published after the pin loop's debounce; the search `staleTime` of 60 s means toggling a filter back and forth is served from cache.
