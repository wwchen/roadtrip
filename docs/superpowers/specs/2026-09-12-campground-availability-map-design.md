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
3. **M3** — Check availability polls the in-view set via the bulk endpoint (new summary view),
   checking state, re-sorted result list. See "M3 design" below.
4. **M4** — pins recolour by status, legend colour key with counts, Only ones we can check.
5. **M5** — mobile parity (bottom sheet, folded filter summary, status strip, progress bar).

## M2 design (2026-09-13, revised)

Approved direction, 2026-09-13: **the backend filters.** M2 adds two campground endpoints and
the frontend builds the filter controls and the list on top of them. This supersedes the
"no new backend for M2" scoping note above. Every kind and amenity label the UI shows is the
backend's own (`CampsiteKind.label`, `AmenityKey.label`); the mock's wording is not a source.

### Why two endpoints

`POST /api/pois` stays the pin layer: every category, sampled, with a per-category budget and
`truncated`. It is what the initial page load and every pan use for pins. The campground
**list** needs an exact answer over one category with a filter applied, plus per-campground
data that today costs two requests per card (detail + campsites, up to 100 round trips for the
50-card cap). So:

| Endpoint | Request | Response | Purpose |
|---|---|---|---|
| `POST /api/campgrounds/search` | `{ boundary: GeoJSON Polygon/MultiPolygon, filter?: CampgroundFilterDto }` | `{ campground_ids: number[], total_in_boundary: number, total_matching: number, truncated: boolean }` | Which campgrounds inside a boundary satisfy the filter. |
| `POST /api/campgrounds/details` | `{ campground_ids: number[] }` | `{ campgrounds: CampgroundSummaryDto[] }` | Bulk summaries for the ids the list will render. |

Identity: `campground_ids` are **POI ids** (the `pois` row of category campground), because
the cards, pins and drawer already key on POI id; the service resolves them to `campgrounds.id`
through `poi_campground`, as `PoiServingRepo` does today. The boundary is GeoJSON rather than a
bbox so the same route serves the viewport, a framed region, and a route corridor; the repo
reuses the `ST_Within(ST_Centroid(geom), poly)` shape already in `PoiServingRepo`.

### Filter and summary DTOs

The filter schema is derived from the summary DTO: every filterable field on
`CampgroundSummaryDto` has a counterpart on `CampgroundFilterDto`, and the TypeScript types for
both come from `make api-types`, never by hand.

`CampgroundSummaryDto` (`@Serializable`, in `model/api/campground/`):

| Field | Type | Source |
|---|---|---|
| `id` | `Long` | POI id |
| `campground_id` | `Long` | `campgrounds.id` |
| `name` | `String` | as `GET /api/pois/{id}` |
| `region`, `agency` | `String?` | as `GET /api/pois/{id}` |
| `lng`, `lat` | `Double` | POI centroid |
| `rating` | `RatingDto?` | as detail |
| `availability_supported`, `booking_system` | `Boolean`, `String?` | as detail |
| `amenities` | `List<AmenityDto>` | `campgrounds.amenities` (key, label, present) |
| `site_counts` | `Map<String, Int>` | campsites per `CampsiteKind` wire value |
| `site_total` | `Int` | live campsites |
| `max_people` | `Int?` | max over campsite `max_people`; null when no site carries one |

`CampgroundFilterDto`:

| Field | Type | Matches when |
|---|---|---|
| `site_type` | `String?` (`CampsiteKind` wire) | `site_counts[site_type] > 0` |
| `group_size` | `Int?` | `max_people >= group_size` **or `max_people` is null** |
| `amenities` | `List<String>` (`AmenityKey` wire) | each key is listed with `present = true`, **or the key is absent** |

`checkable_only` (M4's toggle) is not in M2a: `availability_supported` is decided by the booking identity resolver in the service, not by a column, so a server-side predicate for it needs its own design.

**No data is not a miss.** A campground the provider gives no field for passes the filter, and
the card shows the facet in its no-data state. Only a stated miss (`present = false`, a people
count below the group, a zero count for the kind) drops it. This is how the backend filter and
the issue's "nothing drops out silently" rule reconcile: what is dropped is exactly what the
data says does not fit, and the list head reports the drop ("9 of 17 in view match").

### Aggregates are computed on read

`site_counts`, `site_total` and `max_people` come from a SQL function, `campground_site_summary`,
that aggregates `campsites` per campground over a covering index rather than from a table
refreshed on catalog load. Postgres inlines the function as a LATERAL join, so the aggregate
stays correlated to one campground and walks the index instead of grouping the whole table; on
the full local catalog (457k campsites) that measured 24 ms at the widest allowed view and under
2 ms for a 50-id details read. A plain VIEW was rejected: the planner cannot push the join key
through its `GROUP BY`, so it aggregates every campsite on every request, measured at 48 s. No
table, no refresh, no staleness.

### Caps and errors

- `search`: results are ordered by distance from the boundary centroid and capped at a
  config-driven `CampgroundSearchConfig.maxResults`; `truncated = true` past it. An empty or
  invalid boundary is `400`. A boundary whose bounding box exceeds
  `CampgroundSearchConfig.maxBoundaryAreaSqDeg` (default 500 sq deg, well past a zoom-6 view) is
  also `400 bad_boundary` — the map never legitimately sends more than that.
- `details`: capped at `CampgroundSearchConfig.maxDetailIds` (same idea as
  `BulkAvailabilityConfig.maxPois`); more is `400` with a `too_many_ids` error. Unknown ids are
  omitted, not an error.
- Both are `ApiContract` rows with every body they serve; route tests produce each success body
  so `contractLedgerCheck` passes. Layering is routes → `CampgroundSearchService` → repos.

### Frontend

- **State.** `campgroundFilterStore` (Zustand, `frontend/src/stores/`): `siteType`, `groupSize`,
  `amenities`, `dateWindow`, `clearFilters`, `reset`. Session-only. Read by trip (M2), polling
  (M3), map (M4).
- **Fetching.** `useCampgroundSearch({ paused })` reads the viewport and the active filter from
  their stores and calls `search` on the debounced viewport (same `VIEWPORT_DEBOUNCE_MS` as the
  pin loop), building the request from the viewport bbox as a GeoJSON polygon; `paused` is the
  one gate for "something else already owns the list" (the caller decides — TopBar passes
  whether a route does), on top of the hook's own zoom-threshold gate. `useCampgroundSummaries(ids)`
  caps its ids at `MAX_IN_VIEW_CARDS`, then calls `details` for the ids not already in the Query
  cache and fans the response into per-id entries under `queryKeys.campgrounds.summary(id)`,
  staleness matching the catalog's five minutes. The M1 detail + campsites pipeline for the
  in-view list is retired; `useSiteCounts` goes with it. The route list keeps its own pipeline.
- **Cards** render from `CampgroundSummaryDto`: name, region, agency, rating, count line,
  facets. Count line with a type selected: `"{site_counts[type]} {kindLabel} sites · {site_total}
  total"` when they differ, `"{site_total} {kindLabel} sites"` when equal; no type: M1's
  `"{site_total} sites"`; zero total renders no line. `rankCards` (M2c) then moves known
  matches — no `no-data` facet — above cards with one, keeping nearest-first order within
  each group.
- **Pins follow the filter** (M2c). While a filter is active, `TopBar` publishes the search's
  full matching id set (not the 50-card slice) to `mapStore.campgroundFilterIds`; `useMapOverlays`
  folds it into the campground layer filter alongside the hidden-agency clause, so pins the
  filter excludes disappear from the map, and `useViewportPois` drops the same pins before
  counting, so the legend's campground and agency counts match what the filter matches. With
  no filter active the id set is null and nothing about painting or counting changes.
- **Facets** (`features/trip/facets.ts`, pure): one per active filter, one of three states —
  `match`, `miss`, or `no-data`. A miss should not reach the list, since the server already
  filters on the active facets; when one does anyway (a stale card, a filter changed after the
  search landed), the frontend renders it honestly rather than hiding the discrepancy — close
  icon, `--rt-muted`. Labels: kind label; "Up to N" / "Group size"; amenity label from the DTO.
  Match icon `check` (colour `--rt-text`); no-data icon `help` (colour `--rt-faint`).
- **Heads.** Filter block: "Campgrounds" and `"{matching} of {total_in_boundary} in view"` when
  a filter is active, else `"{total_in_boundary} in view"`. List head as M1:
  `· N · M checkable online`.
- **Controls** (desktop only; mobile is M5), all via `@ui`: the **Filter campgrounds** pill
  with hint "Then check dates" and an active-count badge; `SegmentedControl` for site type
  (Any + `tent`/`rv`/`cabin` labels); `GroupSizeStepper` (two icon `Button`s, clamped
  2..12, below the minimum turns the filter off; Storybook story); `Chip` per exposed amenity
  (`toilets`, `showers`, `water`, `pets_allowed` in a named const); and `DateWindowFields`
  (Storybook story): Start date and End date fields with a `MonthCalendar` from `@ui` that shows
  only while a field is selected. Each pick fills that end and moves on to the one still
  missing; the calendar closes once both are set, and days before today are disabled. Like the
  other filters it applies as it changes — `dateWindow` is written as soon as both ends are set
  and cleared while either is missing — with "N nights" and a Clear. (It first shipped as a
  **Check availability** `Banner` with **Add dates**, two plain-text `YYYY-MM-DD` fields and
  Save, because `@lew-ds/lds-react` never calls `onChange` for `type="date"`; `@ui`'s
  `TextField` now relays those types.) Labels for
  kinds and amenities come from one table in `lib/campground-vocab.ts` mirroring the backend
  enums.
- Copy in `lib/strings.ts` (`filterCopy`); CSS in `features/trip/topbar.css`, tokens only.

### Viewport clipping (M1 follow-up, pins side)

`useViewportPois` buckets the whole cached response, and the containment cache answers a
sub-view with a superset, so legend counts described a wider, older view. Clip features to the
request bbox before `bucketPins`. The list no longer reads that cache once it moves to
`search`, but the legend still does. Ships first as its own small PR.

### Milestone split

- **M2a (backend):** migration + ETL aggregate, the two DTO pairs, `CampgroundSearchService`,
  repos, routes, contract rows, route tests, `make api-types`.
- **M2b (frontend):** store, hooks, cards on summaries, facets, controls, banner, retiring the
  per-card pipeline for the in-view list.

## M3 design (2026-10-12)

The dates apply like the other filters (see M2's controls), so the check is a separate,
explicit **Check availability** button beside them, shown once both dates are set: it is the
one control that talks to booking providers, and a date tweak should not fan out to them.

### Backend: the bulk endpoint's `summary` view

`POST /api/pois/availability/bulk` takes two more request fields, and keeps its contract row:

| Field | Type | Meaning |
|---|---|---|
| `view` | `BulkAvailabilityView`: `campsites` (default) \| `summary` | what each entry carries |
| `group_size` | `Int?` | keep campsites with `max_people >= group_size` **or no stated capacity** (no data is not a miss, as in the M2 filter); applies to both views; `< 1` is `400 bad_group_size` |
| `fresh` | `Boolean` (default false) | answer only from observations at most `bulk.fresh-tolerance` (1 m) old instead of `bulk.tolerance` (2 h), asking the providers again for the rest — the map's Check again |

Under `summary` each entry carries `summary: BulkPoiSummaryDto` instead of `campsites`
(an error entry still carries only `error`):

| Field | Meaning |
|---|---|
| `status` | `StayAvailabilityStatus`: `open` (more than `FEW_LEFT_MAX_SITES` = 2 open), `few_left` (1–2), `full`, `closed_for_season`, `no_data` |
| `open_sites` | campsites bookable online (`available`, not `first_come`) every night from `start_date` up to, not including, `end_date` |
| `checked_sites` | campsites the answer is over: the requested type, fitting the group |
| `cache` | the batch's cache block, so the client can say how old the answer is |

With nothing open, only the campsites the provider said anything about are judged: the status
is `closed_for_season` when every one is closed on at least one night (the season ends, or has
not begun, within the stay — it cannot be had whole, and "full" would send someone hunting for a
cancellation),
`full` when every one has a night that rules it out (taken, closed, walk-up only), and
otherwise `no_data` — some site has unanswered nights and none against it, so it may be open
(a stay running into a month the provider did not return). No site matched, or no night
answered at all, is `no_data` too. `StaySummary.kt` decides it from the same per-night statuses
the week grid fuses; the browser renders the word it is given.

The endpoint's per-IP limit (10/min) keys on `origin.remoteHost`, which behind a proxy would be
the proxy for every caller. Ktor's `XForwardedHeaders` plugin, with a handler that takes only the
address, makes it `callerFromForwardedFor`: the rightmost `X-Forwarded-For` entry past
private-network hops, compared without a port — what the edge proxy appended, however many inner
hops follow it, and never anything a caller wrote. Scheme, host and port are not taken from the headers. The backend names
no proxy or vendor; it relies only on every request arriving through a proxy that appends its
peer, which holds while no backend port is published (outside production a bypassable limit is
fine).

Under `view: summary`, `min_nights` other than its default (1) is refused as `bad_min_nights`:
the summary is about every night of the window, so a shorter run would be a different question.

### Frontend

- **The check** (`features/trip/useStayCheck.ts`, pure half in `stay-check.ts`). It asks
  about the in-view cards (the ≤50 rendered) that are `availability_supported`, in at most
  `MAX_REQUESTS_PER_CHECK` = 4 sequential requests, each given `CHECK_REQUEST_TIMEOUT_MS`
  (60 s) before the check stops as timed out. The first check answers from recent
  observations; **Check again** of the same question sends `fresh: true`. Batching fills the
  cards in batch by batch and keeps a check well inside the endpoint's per-IP limit. Sequential, because the backend fans
  each request out against a small connection pool. A throttled request (`ip_throttled`)
  stops the check; what landed stays, and the button stays disabled for a minute
  (`THROTTLE_COOLDOWN_MS`, one timer at the deadline — `useBefore`), through a change of dates
  too, since the limit is per caller. Why the button is off — that cooldown, a check that
  stopped or timed out, or nothing in the loaded list a provider can answer for (not said
  while the list is still loading or zoomed out) — is said beside it, so a folded list cannot
  hide it; with no dates there is no button and no note.
- **State** (`stores/stayCheckStore.ts`): the question asked (`StayQuery`: dates, site
  type, group size), the ids asked, each answer, whether it is running, and when the
  oldest answer was observed. Answers show only while the question matches the current
  filter store (`sameStayQuery`); new dates or site filters stop a running check and drop
  its answers. Read by the list now and by the pins in M4.
- **The list.** While running, every card keeps its place and fills in ("Checking…", then
  its answer); the head reads "Checking 5 of 13…". Once back, cards sort by status (open,
  few left, full, closed, no data, couldn't check, not asked), then most open sites, then
  arrival order (`rankByStay`), and the head reads "Fri Sep 11 → Sun Sep 13 · 8 with a site
  · 1 full · 4 closed for the season · 3 not bookable for these dates · 2 unknown · Checked 4m
  ago" — closed counted apart from booked up, refusals (`STAY_REFUSAL_CODES`) apart from
  outages, and the age is the stalest answer's, not the click's.
- **Card lines**: "8 sites open", "2 sites left", "Full", "Closed for the season", "No data
  for these dates", coloured by the `--rt-stay-*` roles. A per-POI refusal says why when its
  code does ("Not bookable this far ahead yet" for `beyond_booking_horizon`, "Too soon to book
  online" for `start_before_earliest`); any other error is "Couldn't check".
- **One read.** `useInViewCampgrounds` reads the check once, orders the cards by it and hands
  the same value to `TripResults`, so a card's line and its place cannot disagree. The head's
  "Checked 4m ago" re-reads the clock every 30 s (`useNow`).

## Out of scope for the issue

- Creating availability watches from the result list.
- A pre-dates "open for season" filter.
- Changing provider adapters.
