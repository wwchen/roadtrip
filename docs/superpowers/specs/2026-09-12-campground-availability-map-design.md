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

## Out of scope for the issue

- Creating availability watches from the result list.
- A pre-dates "open for season" filter.
- Changing provider adapters.
