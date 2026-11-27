# Agency in the Campground Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move campground agency selection from the Layers panel into the filter panel as a show-only multi-select, and apply the filter-panel design pass.

**Architecture:** Agency pick lives in `campgroundFilterStore`. The TopBar publishes it to `mapStore` beside `campgroundFilterIds`, so the map layer reads it the same way. A domain-free `@ui` `MultiSelect` renders the dropdown. Shared `@ui` field styles replace the date-only field classes.

**Tech Stack:** React 19, TypeScript, Zustand, MapLibre, LDS (`@ui`), Vitest + Testing Library, Storybook.

**Spec:** `docs/superpowers/specs/2026-11-27-agency-in-filter-design.md`

## Global Constraints

- Show-only: `agencies` empty means every agency; non-empty means only those.
- An agency-only pick never changes `selectFilterDto` (it stays `undefined`).
- The menu shows a search box when there are more than 10 options (`SEARCH_THRESHOLD = 10`).
- No new code comments (project memory). No inline magic constants. Features import across features only via `@/lib`, `@/ui`, `@/map`, `@/stores`.
- Copy: pill `Filter`; labels `Agency`, `Site type`, `Group size`, `Amenities`; `All agencies`; `Clear`; group size off shows `Any`.

## Review Focus

- A picked agency pans out of view: it stays in the dropdown with count 0 and can be unticked (Task 3 test).
- An agency-only pick, with no catalog filter: the badge shows 1, the count reads "N of M", and the server request has no filter (Task 1 and Task 4 tests).
- Uncategorized picked: pins with no `agency` property are drawn and named pins are not (Task 2 `campgroundLayerFilter` test).
- A shared link to a campground outside the pick: its agency is added to the pick (Task 5 test).
- A route is computed while an agency is picked: the corridor list is not narrowed by agency (Task 5 `routeList` test).

---

### Task 1: Filter store owns the agency pick

**Files:** Modify `frontend/src/stores/campgroundFilterStore.ts`, `campgroundFilterStore.test.ts`

**Produces:**
- State and actions: `agencies: string[]`, `toggleAgency(agency: string): void`, `includeAgency(agency: string): void` (adds it only when the pick is non-empty and lacks it), `clearAgencies(): void`.
- `clearFilters` also empties `agencies`.
- `selectCatalogFilterCount(s): number` covers site type, group size and amenities.
- `selectActiveFilterCount(s)` = catalog count + (agencies non-empty ? 1 : 0).
- `selectFilterDto` gates on `selectCatalogFilterCount`.

- [ ] Tests:
  - `toggleAgency` adds a name, then removes it.
  - With only an agency picked, `selectActiveFilterCount` is 1, `selectCatalogFilterCount` is 0, and `selectFilterDto` is undefined.
  - `includeAgency` is a no-op on an empty pick and adds the name to a non-empty one.
  - `clearFilters` empties `agencies` and keeps `dateWindow`.
- [ ] Implement, then run `npx vitest run src/stores/campgroundFilterStore.test.ts`. Expect PASS.

### Task 2: Map helpers and mapStore switch to show-only

**Files:**
- Modify `frontend/src/map/agencies.ts` and its test.
- Modify `frontend/src/stores/mapStore.ts` and its test.
- Modify `frontend/src/features/map/useMapOverlays.ts`, `useViewportPois.ts`, `useDeepLinkedPoi.ts` and their tests.

**Produces:**
- `excludedAgencyPinIds(features, picked: readonly string[]): ReadonlySet<number>` replaces `hiddenAgencyPinIds`. A pin is excluded iff the pick is non-empty and its agency is not in it.
- `campgroundLayerFilter(picked: readonly string[], matchingIds)`. A non-empty pick adds `['any', ['in', ['get','agency'], ['literal', named]]]`, plus `['!', ['has','agency']]` when `Uncategorized` is picked.
- `agenciesByCount(counts: ReadonlyMap<string, number>, picked: readonly string[]): Array<{ agency: string; count: number }>` sorts by count descending, then by name. Picked agencies that are missing come last with count 0.
- `hiddenAgencyFilter` and `sortedAgencies` are deleted.
- mapStore: `campgroundFilterAgencies: string[]` (empty means none) and `setCampgroundFilterAgencies(agencies: readonly string[]): void` replace `hiddenAgencies`, `setAgencyHidden` and `selectIsAgencyVisible`.
- `ViewportPois.agencies` is removed.
- `useDeepLinkedPoi` calls `useCampgroundFilterStore.getState().includeAgency(featureAgency(data))`.

- [ ] Rewrite the agencies tests for the pick semantics:
  - With pick `['BC Parks']`, a USFS pin is excluded.
  - An empty pick returns the shared empty set.
  - The layer filter for `['Uncategorized']` is `['all', ['any', ['!', ['has','agency']]]]`.
  - `agenciesByCount` orders 21/14/9 and appends a picked `'Gone'` with count 0.
- [ ] Update the mapStore and useDeepLinkedPoi tests. Deep link with pick `['USFS']` to a `'WA Parks'` campground gives pick `['USFS','WA Parks']`. Deep link with an empty pick leaves it empty.
- [ ] Run `npx vitest run src/map src/stores src/features/map`. Expect PASS.

### Task 3: `@ui` MultiSelect and shared field styles

**Files:**
- Create `frontend/src/ui/MultiSelect.tsx`, `multi-select.css`, `MultiSelect.test.tsx`, `MultiSelect.stories.tsx`, `field.css`.
- Export from `ui/index.ts`.
- Move `.tb-dates-field*` rules from `features/trip/topbar.css` into `field.css` as `.rt-field`, `.rt-field__label`, `.rt-field-box`, `.rt-field-box--open`, `.rt-field-box__placeholder`, and point `DateWindowFields.tsx` at them.

**Produces:** `MultiSelect(props: MultiSelectProps)` where

```ts
interface MultiSelectOption { value: string; label: string; count?: number }
interface MultiSelectProps {
  id: string;
  label: string;
  options: readonly MultiSelectOption[];
  selected: readonly string[];
  onToggle: (value: string) => void;
  onClear: () => void;
  allLabel: string;
  allCount?: number;
  triggerText: string;
  searchLabel: string;
  noMatchText: string;
  defaultOpen?: boolean;
}
```

The trigger is an `.rt-field-box` button with `aria-expanded`, and the menu is portalled to `document.body`. Pointer-down outside the menu or Escape closes it, and Escape returns focus to the trigger.

- [ ] Tests:
  - Clicking the trigger opens the menu, and clicking an option calls `onToggle('B')`.
  - "All" is checked when `selected` is empty and calls `onClear`.
  - The search box appears only above 10 options, and filtering hides rows that don't match.
  - Escape closes the menu and focuses the trigger.
- [ ] Run `npx vitest run src/ui src/features/trip/DateWindowFields.test.ts`. Expect PASS.

### Task 4: Filter panel design pass with the agency dropdown

**Files:**
- Modify `features/trip/FilterPanel.tsx`, `GroupSizeStepper.tsx`, `TopBar.tsx`, `useInViewCampgrounds.ts`, `useRouteCampgrounds.ts`, `trip-cards.ts`, `results-list.ts`, `topbar.css`, `lib/strings.ts`.
- Update the tests and stories for FilterPanel, TopBar, TripResults, trip-cards and results-list.

**Consumes:** Task 1 store API; Task 2 `excludedAgencyPinIds`, `agenciesByCount`, `setCampgroundFilterAgencies`; Task 3 `MultiSelect`.

**Produces:**
- `useInViewCampgrounds` reads `agencies` from the filter store. It returns `agencyCounts: ReadonlyMap<string, number>`, computed from `viewportCampgrounds` narrowed to `matchingIds` when a catalog filter is active.
- `FilterPanel` props add `agencyCounts`.
- `CardFilter` becomes `{ agencies: readonly string[]; campgroundsHidden: boolean }`.
- `InViewListFacts.agenciesHidden` becomes `agencyFiltered`, and `listCopy.allAgenciesHidden` is reworded to point at the filter.
- `routeList` drops its agency input.
- TopBar:
  - Publishes `setCampgroundFilterAgencies(enabled && !failed ? agencies : [])` next to the ids.
  - Gates the ids on `selectCatalogFilterCount`.
  - Clears both on unmount.

- [ ] FilterPanel tests:
  - The pill is named "Filter", and "Filter, 1 active" after picking an agency.
  - Picking from the dropdown updates the store.
  - Clear empties the agencies and the catalog filters but keeps the dates.
  - Group size reads "Any" when null.
- [ ] TopBar tests: replace the `hiddenAgencies` cases with `campgroundFilterStore.agencies`. Add a test that an agency-only pick publishes agencies and does not publish ids.
- [ ] Run `npx vitest run src/features/trip`. Expect PASS.

### Task 5: Layers panel without agency rows; prototype removed

**Files:**
- Modify `features/map/LegendPanel.tsx`, `legend.css`, `LegendPanel.stories.tsx`, `TripResults.stories.tsx`.
- Delete `frontend/src/prototypes/`.
- Update `docs/react-migration-plan.md` where it describes legend agency rows.

- [ ] Remove `AgencyRow`, `Count`, `.rt-legend__agencies` and the `AgencyHidden` story.
- [ ] Run `npm --prefix frontend run typecheck && npm --prefix frontend run lint && npm --prefix frontend test`. Expect all to pass.
- [ ] Open Storybook to `Trip/FilterPanel` and `Map/LegendPanel`, and check them visually.
- [ ] Commit with explicit paths.
