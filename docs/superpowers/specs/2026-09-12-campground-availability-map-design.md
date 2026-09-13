# Campground availability in map view — design spec

Source of truth: [wwchen/roadtrip#565](https://github.com/wwchen/roadtrip/issues/565).
Mockups: [Campsite availability mock](https://claude.ai/design/p/cd32181a-f5f1-4015-9d86-55b84182ef18)
(three boards: `1 Map view`, `2 Search and filter`, `3 Availability result`, each with desktop
and mobile). This file mirrors the issue so a plan can cite it; when they disagree, the issue wins.

## Original ask

As a user, I'd like to explore campground availability in map view. The filterable options are:
open for season; available for my dates; my campsite filters (group size, amenities, campsite
characteristics). Today, only search by campground name exists.

## Decisions carried over from the issue thread

- Colour means **category**, never brand. Availability status colours apply to the campground
  layer only, and only once dates are set. Supercharger and Planet Fitness pins are untouched.
- The map "steps into" campground-only mode contextually, the way Google Maps swaps hotel pins for
  price pins after a hotel search. Other layers stay drawn but are not recoloured.

## The flow

Three steps. Steps 1 and 2 need no dates and no network beyond the catalog; step 3 is the only
gated step and the only one that talks to booking providers.

### Step 1: Map view (entry point)

- The "Campgrounds in view" list is available **without a route**. Today the results list only
  appears as "Campgrounds along route" once a trip exists.
- One entry point under the search row: a **Filter campgrounds** pill that opens step 2.
- Pins stay in the campground layer colour until dates are set.
- Each card shows: name, region, agency, rating, and a site count line ("54 sites").
- Mobile: compact search panel top-left, controls on the right, list in a bottom sheet.

### Step 2: Search & filter (local, instant, no dates)

- Site type (single choice: Any / Tent / RV / Cabin, `CampsiteKind` wire values), group size
  (stepper, matched against campsite `max_people`), amenities (multi-select chips from the
  `AmenityKey` vocabulary).
- **Nothing drops out silently.** Every card shows one facet per active filter in one of three
  states: matches, does not match, **no data**. No data must read differently from a miss.
- Type-qualified count line when a type is selected: "48 tent sites · 81 total".
- Head: "17 in view" and "17 · 13 checkable online". Checkable online means
  `availability_supported`. Cards for the rest carry "not checkable online".
- A **Check availability** banner with an **Add dates** button is the only gated step.

### Step 3: Availability result (dates set, providers polled)

- Checking state: progress ("5 · of 13 checked"), each card keeps its place and fills in as its
  answer lands, pins and list order frozen until every provider is back.
- Result state: pins recolour into open / few left (1–2) / full / no data; list re-sorts by
  status then open count; per-card result lines; head "Sep 11 – 13 · 8 with a site · 5 full ·
  4 hidden"; "Checked 4m ago".
- Layers panel: **Only ones we can check** toggle, status colour key with live counts, agency
  checkboxes with counts.
- Mobile: dates take the panel row; filters fold to a summary with **Edit filters**; a status
  count strip; the sheet holds the re-sorted list.

### "Open for season"

Not a separate control. A campground closed for the chosen dates comes back as
`closed_for_season` and lands in the full / no data buckets. A pre-dates season filter needs
catalog season data and is a follow-up.

## Scoping decisions (2026-09-12)

- **No new backend for M1 or M2.** `GET /api/pois/{id}` already carries name, agency, rating, the
  typed amenity list, `availability_supported` and `booking_system`. Cards hydrate the way
  on-route cards already do. Site counts by type and max group size are computed on the
  frontend from `GET /api/pois/{id}/campsites`.
- **Group size stays.** Campsite `max_people` is populated for ~90% of Campflare campgrounds and
  all rec.gov ones; ReserveAmerica and ReserveCalifornia have none, which is what the "no data"
  facet state is for.
- The first backend change is a summary mode on `POST /api/pois/availability/bulk` in M3.

## Milestones

1. **M1** — campgrounds-in-view list without a route, cards hydrated from the detail endpoint,
   site count line, "checkable online" tag. Frontend only.
2. **M2** — local filters with match / miss / no-data facets; Add dates stores the window only.
3. **M3** — Add dates polls the in-view set via the bulk endpoint (new summary mode), checking
   state, re-sorted result list.
4. **M4** — pins recolour by status, legend colour key with counts, Only ones we can check.
5. **M5** — mobile parity (bottom sheet, folded filter summary, status strip, progress bar).

## M2 design (2026-09-13)

Approved in conversation on 2026-09-13. Frontend only; no backend, no generated-type edits.
Every label the UI shows for a kind or amenity is the backend's own (`CampsiteKind.label`,
`AmenityDto.label`); the mock's wording is not a source.

### State: `campgroundFilterStore`

A Zustand store under `frontend/src/stores/`, because the filters are read by the trip
feature (M2), the polling layer (M3) and the map feature's pins and legend (M4), and features
never import each other.

| Field | Type | Default | Meaning |
|---|---|---|---|
| `siteType` | `string \| null` | `null` | A `CampsiteKind` wire value; null is "Any". |
| `groupSize` | `number \| null` | `null` | People count; null means the filter is off. First increment sets `MIN_GROUP_SIZE` (2). |
| `amenities` | `string[]` | `[]` | `AmenityKey` wire values switched on. |
| `dateWindow` | `{ start: string; end: string } \| null` | `null` | ISO dates. M2 stores it; M3 polls with it. |

Setters: `setSiteType`, `setGroupSize`, `toggleAmenity`, `setDateWindow`, `clearFilters`, `reset`.
Session-only; no URL or localStorage persistence. `activeFilterCount` is a selector.

### Data

- `SiteCounts` gains `byKind: Record<string, number>` and `maxPeople: number \| null` (max over
  campsite `max_people`; null when no site carries one). Computed in `site-counts.ts` from the
  campsites response M1 already fetches.
- `TripCard` gains `amenities: readonly { key: string; label: string; present: boolean }[]`, copied
  from the POI detail's `amenities` in `hydrateCard`.
- Amenity chips exposed: `toilets`, `showers`, `water`, `pets_allowed`, listed in a named
  `FILTER_AMENITY_KEYS` const. Chip and segment labels come from one frontend table,
  `lib/campground-vocab.ts`, that mirrors the backend's `AmenityKey.label` and
  `CampsiteKind.label` wire-to-label pairs for the exposed values ("Toilets", "Showers",
  "Water", "Pets allowed"; "Tent", "RV", "Cabin"). Facet labels on a card prefer the label the
  detail response carries and fall back to the table.

### Facets

`features/trip/facets.ts` is pure: `facetsFor(card, counts, filters): Facet[]`, where
`Facet = { key, label, state: 'match' \| 'miss' \| 'no-data' }`. One facet per active filter,
in the order site type, group size, amenities.

| Filter | match | miss | no data |
|---|---|---|---|
| Site type | `byKind[type] > 0` | counts loaded and `byKind[type]` is 0 | catalog empty (`total === 0`) |
| Group size | `maxPeople >= groupSize` | `maxPeople < groupSize` | `maxPeople === null` |
| Amenity | listed with `present: true` | listed with `present: false` | key absent from the list |

Facets render only once the card is hydrated and its counts have landed, the same rule as the
M1 site count. No facet row at all when no filter is active. Nothing is ever removed from the
list by a filter; the agency and layer visibility rules from M1 are the only removals.

Facet labels: site type uses the kind label ("Tent"); group size reads "Up to N" on match or
miss and "Group size" on no data; amenities use the amenity label. Icons: `check`, `close`,
`help` from the sprite; colours `--rt-text`, `--rt-muted`, `--rt-faint`.

### Count line and heads

- Card count line with a type selected: `"{byKind[type]} {kindLabel} sites · {total} total"`
  when the two differ, `"{total} {kindLabel} sites"` when equal, singular-aware. No type:
  M1's `"{total} sites"`. Zero total renders no line (M1 rule).
- Panel head under the filter pill: `"Campgrounds"` and `"{totalInView} in view"`.
- List head is unchanged from M1: `· N · M checkable online`.

### Controls

Desktop only in M2 (mobile folding is M5). All through `@ui`:

- **Filter campgrounds** pill under the search row with the hint "Then check dates". Toggles the
  filter block; open state is local to `TopBar`. Shows a count badge when filters are active.
- Site type: LDS `SegmentedControl`, options `Any` plus `CampsiteKind` labels for `tent`, `rv`,
  `cabin`.
- Group size: LDS has no stepper. `GroupSizeStepper` in `features/trip/`: minus and plus icon
  `Button`s around the count, `aria-label`s "Fewer people" / "More people", clamped to
  `[MIN_GROUP_SIZE, MAX_GROUP_SIZE]` (2..12); stepping below the minimum turns the filter off.
  Storybook story required.
- Amenities: LDS `Chip` with `selected`, one per exposed key.
- **Check availability** banner: LDS `Banner` with the calendar icon, "Check availability" /
  "Add dates to see which of these have a site." and an **Add dates** primary `Button`. Add dates
  reveals two `type="date"` `TextField`s (Start date, End date) as the watch form does, plus
  Save. Save writes `dateWindow` and the banner collapses to a summary,
  "Fri Sep 11 → Sun Sep 13 · 2 nights", with Edit and Clear. End before start disables Save.
  No network call.

Copy goes in `lib/strings.ts` as `filterCopy`. New CSS goes in `features/trip/topbar.css`
under `.tb-filters*` and `.tb-facet*`, tokens only.

### Route list

Unchanged. Filters, facets and the banner apply to the viewport variant only; the route variant
does not fetch campsites and stays as M1 left it.

### Viewport clipping (M1 follow-up)

`useViewportPois` bucketed the whole cached response. The containment cache answers a sub-view
with a superset, so after a card fly-to the list and legend counts described the wider, older
view. Fix: clip features to the current request bbox before `bucketPins`, so map, list and
legend agree. Point-in-bbox on the flat `[west, south, east, north]` the request already
carries. Ships first as its own small PR.

## Out of scope for the issue

- Creating availability watches from the result list.
- A pre-dates "open for season" filter.
- Changing provider adapters.
