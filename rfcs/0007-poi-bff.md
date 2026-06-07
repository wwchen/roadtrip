---
title: POI data model + BFF-shaped API
authors:
  - William Chen
created: 2026-06-07
last_updated: 2026-06-07
rfc_pr: TBD
status: Draft
---

# Proposal: POI data model + BFF-shaped API

## Summary

Treat campgrounds, superchargers, parks, and Planet Fitness as one POI type with
a shared geometry + JSONB-properties shape, surfaced behind a unified
`/api/pois` query interface. The API returns BFF-shaped (backend-for-frontend)
payloads — actions, sections, capability endpoints — so the frontend renders
what the backend assembles instead of re-deriving display rules from raw data.
Eliminates the per-vendor controller logic that has metastasized in the drawer
(rec.gov vs Aspira vs bcparks vs alberta vs google fallback) and consolidates
five disparate fetch paths into one.

## Motivation

Today the codebase has two coupled problems:

**Disparate APIs over what is fundamentally one shape.** Campgrounds, parks
(NP/SP), and Planet Fitness already live in the `pois` table with a shared
schema (point or polygon geometry + JSONB properties). Superchargers do not —
they ship as a static GeoJSON file with their own `/api/superchargers` route,
their own fetch loop, and their own FE rendering. New POI types (dump
stations, scenic viewpoints, gas) face the same fork in the road: extend the
generic POI table or build another bespoke pipeline. The historical accident
of "SC arrived first as a file" has set a bad precedent.

**FE accumulating vendor-specific controller logic.** `web/drawer.js` and
`web/campground-card.js` are increasingly a chain of `if (recgov_id) ... else
if (aspira) ... else if (bcparks_url) ... else if (parks_alberta_url) ...
else search Google`. Adding Aspira this week required: parsing nested-object
properties out of MapLibre's stringification, a separate availability fetch
path, a separate deeplink builder branch, a separate booking-system footer, a
gating rule on the "Watch for openings" relabel. The drawer has six branches
just to decide what the primary button should say. This will not get better
on its own — every new vendor adds one more branch.

If we don't fix this:

- New POI types either get bespoke pipelines (multiplies fetch/render code) or
  get awkwardly shoehorned into pois (e.g. SC stays on disk indefinitely
  because integrating means refactoring the FE too).
- The corridor query (`POST /api/pois?corridor=...`) only finds POIs in the
  pois table. SC corridor filtering is a separate code path. Want to find
  "any POI within 10 mi of my route"? You can't, because there isn't one
  query that crosses types.
- Adding the next vendor (Camis for Alberta provincial, KOA for private,
  Hipcamp) means another availability route, another deeplink builder, more
  drawer branches.

## Goals

- One `pois` table is the source of truth for all POI types, including SC.
- One `/api/pois` query interface accepts geometry filters (bbox, corridor,
  point+radius), name search, and type filters; returns one mixed-type
  response.
- One `/api/availability/{poi_id}` route that internally dispatches to the
  right vendor adapter (rec.gov, Aspira) and returns one shape regardless of
  provider.
- FE drawer renders a section tree the BE assembles. No vendor-specific
  branches in `drawer.js` or `campground-card.js`.
- Strongly-typed Kotlin POI domain model that mirrors the DB schema, with
  per-type properties as a sealed hierarchy.
- Adding a new POI type is: poller target + category enum value + (optional)
  vendor adapter for availability. No new API route, no new FE fetch path,
  no new drawer code.

## Non-Goals

- Server-side clustering / LOD for zoom levels. Mapbox/MapLibre's client
  clustering is good enough; revisit only if perf bites.
- Replacing the campsite availability tracker (`/campsite`) — its UI stays
  rec.gov-specific for the watch flow. Drawer just stops knowing about it.
- A proper i18n layer. Display strings live BE-side; if/when we localize,
  we'll thread Accept-Language through then.
- Schema evolution for the `properties` JSONB blob. Each POI type defines
  its own shape; we won't try to normalize amenities/connectors/aspira-IDs
  into separate tables.
- Changing how layer-toggle checkboxes feel. They still exist in the UI;
  they just translate to `types=` query params instead of toggling layer
  visibility.

## Proposal

### Section 1: API returns a render tree, not raw data

The anchor design choice. `/api/pois` and the click-into-drawer payload
return view-ready structures — actions, sections, capability endpoints —
not raw vendor IDs the FE has to interpret.

A drawer payload looks roughly like:

```json
{
  "header": {
    "title": "Conkle Lake Park",
    "subtitle": "Provincial Park · BC"
  },
  "hero": { "kind": "image", "url": "..." },
  "sections": [
    {
      "kind": "availability",
      "endpoint": "/api/availability/12345?days=30",
      "skeleton": "Checking availability…"
    },
    {
      "kind": "actions",
      "items": [
        { "label": "Book on BC Parks", "href": "https://camping.bcparks.ca/...", "style": "primary", "external": true }
      ]
    },
    {
      "kind": "details",
      "open": true,
      "rows": [
        { "kind": "pills", "label": "Amenities", "items": ["Drinking water"] },
        { "kind": "footer", "tone": "default", "text": "Booking via Aspira NextGen (BC Parks)" },
        { "kind": "footer", "tone": "warn", "text": "Verified 2026-04-01 · check before booking" }
      ]
    }
  ]
}
```

FE has a switch on `section.kind` → render. No vendor branches. Adding a new
section type is: BE emits it, FE adds a renderer. Old FE clients ignore
unknown `kind`s; new section types ship BE-first without breaking old FE.

The `endpoint` capability is the key idea: BE tells FE *where* to fetch
availability for this pin, FE doesn't know whether that hits rec.gov or
Aspira behind the scenes.

### Section 2: Unified data model

Fold superchargers into the `pois` table. Schema additions:

- Extend the `category` CHECK to include `'supercharger'`.
- New poller target: Tesla supercharger CSV/JSON → `pois` rows with
  `source='tesla'`, `category='supercharger'`, point geometry, properties
  carrying connector/stall/voltage details.
- Drop `data/tesla-superchargers.geojson` and the `superchargersRoutes` route
  once the FE migrates.

Every POI type ends up with the same row shape:

```
id | source | source_id | category | name | geom | region | unit_name | properties | reserve_url | ...
```

Per-type fields live in `properties` (JSONB). Type taxonomy:

- `campground` (rec.gov, parks-canada-curated, bc-parks, alberta-provincial)
- `national-park` / `state-park` (geometry is polygon)
- `planet-fitness`
- `supercharger` (new)
- future: `dump-station`, `gas`, `viewpoint`, etc.

#### Entity relationships

The POI graph has more structure than the current single `pois` table
captures. Sketch of the entities + how they relate:

```
                    ┌──────────────────┐
                    │  governing_body  │   NPS, USFS, BLM, Parks Canada,
                    │ id, name, kind,  │   BC Parks, Alberta Parks,
                    │ jurisdiction     │   WA State Parks, Tesla, PF Corp
                    └────────┬─────────┘
                             │ 1
                             │
                             │ N
┌──────────────────┐    ┌────┴─────────────┐    ┌──────────────────┐
│      source      │    │       poi        │    │ booking_provider │
│ id, name,        │ 1..│ id, source_id,   │ N..│ id, name, host,  │
│ poller_kind      │────│ category, geom,  │────│ vendor           │   rec.gov, aspira,
└──────────────────┘  N │ name, props…     │  1 └──────────────────┘   camis, none
                        │ governing_body_id│                           (FK optional)
                        │ booking_prov_id  │
                        │ parent_poi_id    │←┐
                        │ last_seen_run_id │ │
                        │ last_poller_id   │ │ self-FK
                        └────────┬─────────┘ │   (parent must be polygon)
                                 │           │
                                 └───────────┘
                                 N:1 optional
                                 (campground → park)


      ┌──────────────────┐                  ┌──────────────────┐
      │   import_runs    │                  │   poller_runs    │
      │ id, source,      │                  │ id, target,      │
      │ status, …        │                  │ phase, status…   │
      └────────┬─────────┘                  └────────┬─────────┘
               │                                     │
               │ pois.last_seen_run_id               │ pois.last_poller_run_id
               └─────────────────────────────────────┘
                          (every poi row carries both)
```

Relationships:

- **POI → governing_body** (N:1, required). The agency that operates the
  pin. Distinct from `source` because one source can carry many governing
  bodies (rec.gov serves NPS + USFS + BLM + Army Corps; the booking-vendor
  Aspira serves Parks Canada + BC Parks + WA State Parks).
- **POI → booking_provider** (N:1, optional). Where to reserve. Null means
  first-come, first-served or unreservable. Vendor-dispatched availability
  (Section 5) reads this field.
- **POI → POI (parent)** (N:1, optional, self-FK). A campground belongs to
  a park; a park has no parent (or in nested cases, a wilderness area inside
  a national park). Constraint: `parent_poi_id` must reference a row whose
  geom is a polygon and whose category is in (`'national-park'`,
  `'state-park'`). This is denormalization — the spatial relation is
  recoverable via `ST_Within(child.geom, parent.geom)` — but it lets the BE
  cheaply assemble subtitles ("Kicking Horse Campground · Yoho National
  Park") without a spatial join on every render.
- **POI → source** (N:1, required, already modeled as `source TEXT`).
  Eventually move to a real FK once we promote `source` to a table.

`governing_body` and `booking_provider` are small dimension tables (10–20
rows each) — promote-from-string, not free-form JSONB. Display strings that
the BE renders (e.g. "Booking via Aspira NextGen (BC Parks)") read from
those tables instead of being hardcoded in TypeScript or the drawer
assembler.

#### Provenance: stamp the poller run on every row

Today `pois.last_seen_run_id → import_runs(id)` records which **import**
last touched a row, but not which **fetch** brought the data over the wall.
That's the question we'd actually want to answer when triaging "why is this
campground showing yesterday's price?" — was the fetch stale, or did the
import skip it?

Add `pois.last_poller_run_id BIGINT REFERENCES poller_runs(id)`, populated by
the importer at the same UPSERT site that already sets `last_seen_run_id`.
The importer is invoked as part of the parent poller run (Phase.Import); it
already has the parent run id in scope, so plumbing it in is a one-line
change in `Importer.run`.

With this in place, every POI row carries a pointer to:
1. Its last import (`last_seen_run_id` → `import_runs`)
2. The parent poller run that imports rolled up under
   (`last_poller_run_id` → `poller_runs` parent row, which links to its
   fetch + import phase rows via `parent_run_id`)

Querying "which rows came from fetch run #N" becomes one join. Drift between
fetch + import (e.g. fetch succeeded, import partial) shows up as rows
sharing an import run but not a poller run.

Out of scope for this RFC: deciding whether to deprecate `import_runs` in
favor of treating `poller_runs` phase rows as the authoritative audit log.
Both tables are useful today; merging is a separate cleanup.

> **Naming note.** The existing wrapper around fetch + import is currently
> called "ingest" in code (RFC 0004; `ingest_runs`, `IngestController`,
> `/api/admin/ingest/*`). This RFC renames it to "poller" because it more
> accurately describes the schedule-driven, periodic nature of the work
> (poll vendor → write artifact → import). Renaming the existing artifacts
> (table, class, routes, RFC 0004 title) is a separate mechanical PR; this
> RFC adopts "poller" terminology going forward.

### Section 3: Unified query API

One endpoint replaces `/api/pois` (existing), `/api/superchargers`, and
the various ad-hoc fetch patterns:

```
POST /api/pois
{
  "bbox": [west, south, east, north],         // optional
  "corridor": { "type": "Polygon", ... },     // optional (route corridor)
  "near": { "lng": ..., "lat": ..., "radius_m": 5000 },  // optional
  "q": "banff",                               // optional name search
  "types": ["campground", "supercharger"],    // optional type filter
  "limit": 2000
}
```

Returns a `FeatureCollection` where each feature carries:

- `geometry` (point or polygon)
- `properties.type` — the renderer dispatches on this
- `properties.display` — title, subtitle, hero hint (BE-shaped)
- `properties.actions[]` — pre-assembled action descriptors
- `properties.features` — capability map (`availability`, `pricing`, ...)
  with endpoint URLs FE GETs

The corridor query is one DB hit (`ST_Intersects(geom, corridor)`), regardless
of whether SC, campgrounds, or parks fall inside.

### Section 4: Strongly-typed Kotlin POI model

Shared base + sealed per-type properties:

```kotlin
sealed class Poi {
    abstract val id: Long
    abstract val source: String
    abstract val sourceId: String
    abstract val name: String
    abstract val geom: org.locationtech.jts.geom.Geometry
    abstract val region: String?

    data class Campground(
        override val id: Long, /* shared fields */,
        val provider: BookingProvider?,    // RecGov, Aspira, BcParks, AlbertaParks, None
        val recgovId: String?,
        val aspira: AspiraIds?,
        val amenities: List<String>,
        val sites: Int?,
        val season: String?,
        val reservable: Boolean?,
        val lastVerified: LocalDate?
    ) : Poi()

    data class Supercharger(
        /* shared */,
        val stallCount: Int,
        val maxPowerKw: Int,
        val connectors: List<ConnectorType>
    ) : Poi()

    data class Park(
        /* shared */,
        val parkType: ParkType,    // National, State, Provincial
        val unitName: String?,
        val acres: Double?
    ) : Poi()

    data class PlanetFitness(/* shared */) : Poi()
}
```

jOOQ codegen owns the shared fields; per-type properties parse from JSONB
explicitly. The `Poi` -> drawer payload assembly is a `fun
buildDrawerPayload(poi: Poi): DrawerPayload` that pattern-matches on the
sealed type — one place for all display-rule logic.

### Section 5: Vendor-dispatched availability

Replace `/api/campsite/availability/{recgov_id}` and
`/api/campsite/availability-aspira/{tx}/{mapId}` with one route:

```
GET /api/availability/{poi_id}?days=30
```

The handler:
1. Looks up the POI by id.
2. Reads `provider` (or peeks at `aspira`/`recgov_id` properties).
3. Dispatches to the matching `AvailabilityProvider`:
   - `RecGovAvailability` → existing /api/campsite client
   - `AspiraAvailability` → existing AspiraClient
   - `NoneAvailable` → returns `{state: "no_provider"}`
4. Normalizes to the existing per-day status response shape.

The drawer payload's `availability.endpoint` always points here — FE doesn't
know which provider answered.

### Section 6: FE consolidation

After Sections 1–5 land, the FE drawer becomes:

- `web/drawer.js` — one `renderSection(section)` switch on `section.kind`.
  No flatten step. No nested-object parse hacks. No vendor branches.
- `web/campground-card.js` — gone. The "shared rendering" was an artifact
  of popups + drawer paths, both now driven by section trees.
- `web/superchargers.js` — gone. SC features come through `/api/pois` like
  everything else; the only SC-specific code left is map style for the
  point layer.
- Map layer install paths consolidate. One generic point layer that filters
  on `properties.type`, one generic polygon layer for parks. Layer toggles
  edit a `selectedTypes` set that gets sent as `types=` in queries.
- `flattenPoi` deletes itself. The BE returns properties in their final
  shape — no JSONB string round-trips.

### Migration plan

This is not a single PR. PR sequence (each independently mergeable):

1. **PR 1: Tesla → pois.** New poller target writes SC into pois. Old
   `/api/superchargers` route stays. `pois` rows now include SC; nothing
   reads them yet.
2. **PR 2: Sealed Kotlin Poi model + drawer payload assembler.** Add the
   types and the `buildDrawerPayload` function. New `GET /api/pois/{id}/drawer`
   endpoint exposes it. FE doesn't use it yet.
3. **PR 3: FE drawer renders from payload.** Drawer fetches the new endpoint
   on click instead of building HTML from feature properties. Vendor branches
   delete.
4. **PR 4: Unified availability route.** `GET /api/availability/{poi_id}`
   dispatches by provider. Drawer payload references it.
5. **PR 5: Unified query API.** `POST /api/pois` accepts `types=` and
   replaces `/api/superchargers`. FE switches its fetch loop. SC GeoJSON
   file deletes.
6. **PR 6: Cleanup.** Remove `flattenPoi`, `campground-card.js`, dead
   per-layer fetch paths, and any vendor-specific FE code that survived.

## Rationale

**Why server-assembled (BFF) over rich client?**

We're a small team with one FE consumer. The BFF tradeoff costs us deploy
coupling (BE + FE move together for new section types) but saves us from the
N-vendor-times-M-display-rule explosion that's already 60% of the drawer
code. With one consumer, BFF is the cheaper place for that complexity.

If we ever add a second consumer (CLI, mobile app), we'd reconsider — at
which point a typed gRPC/protobuf contract probably beats both.

**Why fold SC into pois instead of a parallel "vehicles" table?**

SC has the same shape as everything else: point geometry, name, source,
external URL. The only thing that's unique is its properties JSONB
(connectors, stalls). Splitting it out doesn't pay for itself; we'd just
duplicate the geometry/source/import-runs infrastructure.

**Why a sealed Kotlin hierarchy instead of generic `Poi` with `properties:
JsonObject`?**

Today's `PoiRow` is the latter, and it leaks JSONB-shaped concerns
everywhere. A sealed type forces the per-type shape to be named once and
referenced thereafter. The display-rule logic in `buildDrawerPayload` becomes
exhaustive (compiler-checked) over POI types.

**Why not GraphQL?**

The BFF pattern handles our shape needs without a query language. The cost
of GraphQL (resolver fan-out, N+1 risks, tooling) doesn't pay back when
there's one consumer with maybe ten distinct queries.

**Why one availability route instead of provider-specific routes?**

The provider is BE-side knowledge. Today's routes leak it to the FE. One
route + internal dispatch is the minimum API surface; vendor adapters can
proliferate without FE changes.

**Trade we're making vs. the current architecture:**

- BE gets fatter (display-rule logic, action assembly, vendor dispatch).
- FE gets thinner (pure rendering primitives + a `kind` switch).
- Schema changes to drawer payloads need coordinated deploys, but old
  clients tolerate new section kinds (skip-unknown).
- The `properties.type`-keyed renderer means a new type's first PR is just
  BE-side; FE can ship a fallback render until a custom one is added.

## Unresolved questions

- **Where does pricing live?** Today there's `/api/pricing/{slug}` for
  campground pricing data. It fits the `features` capability map shape
  (`features.pricing.endpoint`), but the slug-vs-id schism needs cleanup.
  Plausible: pricing keys on `(source, source_id)` directly.
- **Park polygons + child campgrounds.** A national park is a polygon; the
  campgrounds inside it are points. Right now they're both in `pois`,
  unrelated. Should there be a parent/child link? (Probably yes, but
  out-of-scope for this RFC unless it changes the drawer payload shape.)
- **Search index.** Issue #67 calls out that name-search is viewport-bound.
  The unified `/api/pois?q=` solves it server-side, but is one query per
  keystroke acceptable load? May need a debounce + a separate
  `/api/pois/search-index` boot payload (lightweight `{id, name, kind, lng,
  lat}` for the whole dataset, ~200KB gzipped).
- **Versioning the drawer payload schema.** Skip-unknown handles new section
  kinds, but a *changed* shape (e.g. `actions` becomes nested) breaks old
  clients. Do we add a `schema_version` field, or rely on FE+BE deploying
  together? Currently they always do, so probably the latter.
- **Caching.** `/api/pois` responses can be Cloudflare-cached when no `q=`
  or per-user filter is set; the drawer payload probably can't be (carries
  per-pin computed display strings). Worth measuring before optimizing.

## Decision log

| # | Date | Decision | Rationale |
|---|---|---|---|
| 1 | 2026-06-07 | API returns view-ready render tree, not raw data | Anchor of the proposal — keeps display logic in one place (BE) and FE thin |
| 2 | 2026-06-07 | Fold SC into `pois` table | Same shape as other types; parallel pipeline doesn't pay for itself |
| 3 | 2026-06-07 | Sealed Kotlin POI hierarchy over generic Poi | Compiler-checked display-rule logic; per-type properties named once |
| 4 | 2026-06-07 | One `/api/availability/{poi_id}` with internal vendor dispatch | Provider is BE-side knowledge; minimum API surface |
| 5 | 2026-06-07 | Stamp `pois.last_poller_run_id` alongside existing `last_seen_run_id` | Lets us trace any row back to the fetch + import that produced it; one-line plumbing change |
| 6 | 2026-06-07 | Promote `governing_body` and `booking_provider` to dimension tables; add `pois.parent_poi_id` self-FK | Captures the agency-vs-source distinction (rec.gov serves multiple agencies) and lets the BE cheaply roll up "campground → park" subtitles without spatial joins |
| 7 | 2026-06-07 | Rename "ingest" to "poller" in this RFC's vocabulary | "poller" describes the periodic, schedule-driven nature of the work better than "ingest"; existing artifacts (`ingest_runs` table, `IngestController`, `/api/admin/ingest/*`) rename in a separate mechanical PR |
