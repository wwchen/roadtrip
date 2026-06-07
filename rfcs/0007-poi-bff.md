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
- `national-park` / `state-park` (geometry is polygon or multi-polygon —
  many parks come in non-contiguous chunks, e.g. island clusters or
  detached recreation areas)
- `planet-fitness`
- `supercharger` (new)
- future: `dump-station`, `gas`, `viewpoint`, etc.

The existing `geom geometry(Geometry, 4326)` column already accepts any JTS
geometry — Point, LineString, Polygon, MultiPolygon. No schema change is
needed to hold multi-polygon parks; the only new constraint is the
parent-FK rule above. If we ever add LineString POIs (trails, rivers, scenic
drives), the same column accommodates them without migration.

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
- **POI → booking_provider** (N:1, optional, via `provider_ref` JSONB).
  Where to reserve and the per-pin IDs the provider's adapter needs. Null
  means the pin isn't on any booking platform we integrate with —
  whether that's FCFS, season-closed, or a vendor we don't speak yet is
  the availability route's call (see Section 5 + the `reservable` note in
  Section 4), not a row-level fact. The vendor-dispatch availability route
  reads this field.
- **POI → POI (parent)** (N:1, optional, self-FK). A campground belongs to
  a park; a park has no parent (or in nested cases, a wilderness area inside
  a national park). Constraint: `parent_poi_id` must reference a row whose
  geom is areal (Polygon or MultiPolygon — `GeometryType(geom) IN
  ('POLYGON','MULTIPOLYGON')`) and whose category is in (`'national-park'`,
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
    abstract val region: String?               // US state / Canadian province
    abstract val country: String?              // ISO 3166-1 alpha-2 (US, CA)
    abstract val governingBodyId: Long
    abstract val parentPoiId: Long?
    abstract val phone: String?
    abstract val address: Address?             // street/city/postcode bag
    abstract val infoUrl: String?              // non-booking informational link
    // Lifecycle: createdAt/updatedAt DB-managed on UPSERT; lastVerified is
    // an editorial touch (someone confirmed the row still reflects reality).
    abstract val createdAt: Instant
    abstract val updatedAt: Instant
    abstract val lastVerified: LocalDate?

    data class Campground(
        /* shared fields */,
        val providerRef: ProviderRef?,         // BookingProvider FK + opaque ref blob
        val amenities: List<String>,
        val activities: List<String>,
        val sites: Int?,
        val season: String?,                   // "mid-May to early October", parsed BE-side
        val near: String?,                     // "5mi NE of Tonasket"
        val photoUrl: String?,
        val cellCoverage: Map<Carrier, CellSignal>?,  // rec.gov-enriched
        val ratingReviews: RatingSummary?      // rec.gov-enriched (avg + count)
    ) : Poi()

    data class Supercharger(
        /* shared */,
        val stallCount: Int,
        val maxPowerKw: Int,
        val facility: String?,                 // "hotel parking", "mall lot"
        val connectors: List<ConnectorType>
    ) : Poi()

    data class Park(
        /* shared */,
        val parkType: ParkType,                // National, State, Provincial
        val designation: String,               // "National Park" vs "Wilderness" vs "Recreation Area"
        val officialName: String?,             // PAD-US Loc_Nm if it differs from name
        val acres: Double?
    ) : Poi()

    data class PlanetFitness(
        /* shared */,
        val openingHours: String?              // OSM-format ("Mo-Fr 05:00-23:00")
    ) : Poi()
}
```

jOOQ owns shared fields. Per-type properties parse from JSONB explicitly.
`buildDrawerPayload(poi: Poi): DrawerPayload` pattern-matches on the
sealed type — one place for all display-rule logic.

`last_verified` (currently a JSONB-only campground audit field) gets
promoted to a real column. It applies to every POI eventually and
becomes filterable.

**`ProviderRef`** is the per-POI booking-provider lookup payload:

```kotlin
data class ProviderRef(
    val providerId: Long,    // FK to booking_provider
    val ids: JsonObject      // shape determined by the provider's adapter
)
```

```json
// rec.gov
{"providerId": 1, "ids": {"recgov_id": "232857"}}
// Aspira NextGen (PC / BC / WA share this shape)
{"providerId": 2, "ids": {"transactionLocationId": -2147483630, "mapId": -2147483388, "resourceLocationId": -2147483624}}
// Camis (Alberta), if/when added
{"providerId": 3, "ids": {"facility_id": "BVPP"}}
```

Stored as JSONB. The `ids` schema is the adapter's contract — validation
lives in the adapter, not at row insert. The availability route (Section
5) hands `ids` to the matching adapter and never inspects it.

#### `reservable` is computed, not stored

The current `reservable: Boolean?` column is a lie: it depends on
season-vs-today, `providerRef` presence, and live availability state.
The availability route returns a normalized status (`available` /
`partial` / `closed_for_season` / `no_provider` / `unreservable_today`)
and the BE composes the CTA from that. Drop the column. `seasonVerdictHTML`
on the FE deletes alongside.

### Section 5: Vendor-dispatched availability

Replace `/api/campsite/availability/{recgov_id}` and
`/api/campsite/availability-aspira/{tx}/{mapId}` with one route:

```
GET /api/availability/{poi_id}?days=30
```

The handler:
1. Looks up the POI by id.
2. Reads its `provider_ref` (FK to `booking_provider` + opaque `ids` blob;
   see Section 4).
3. Loads the registered `BookingProviderAdapter` for that provider FK and
   hands it the `ids` blob.
4. Adapter answers (or returns `no_provider` if `provider_ref IS NULL`),
   normalized to one per-day status response shape.

Adding a new provider is one Kotlin class implementing the adapter
interface + a row in `booking_provider`. No new API route, no new field on
the POI type, no FE change.

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

### Section 7: Thin Python fetchers, Kotlin owns ETL

Today, transform is split across two languages: Python `fetch_*.py`
scripts map type codes, merge across files, and write FE-shaped geojson;
Kotlin `Importer` parses that geojson and re-transforms (enrichment,
parent lookups). Move the line: Python writes raw upstream bytes, Kotlin
owns parse + transform + merge + enrich + upsert.

```
Python fetcher  ──►  data/raw/<source>/<ts>.<ext>  ──►  Kotlin ETL  ──►  pois
   (HTTP, auth,         (verbatim upstream bytes,           (parse, transform,
    cookies, rate-       timestamped, retained)              merge, enrich,
    limit only)                                              mark-and-sweep)
```

**Raw cache layout** (`data/raw/<source>/`):

- `YYYY-MM-DDTHH-MM-SSZ.<ext>` per capture (UTC, `-` for `:` for FS-safety).
  Multi-file sources nest under a per-capture directory.
- `latest -> <ts>.<ext>` symlink swapped atomically with `ln -sfn`. Kotlin
  ETL only reads `latest/`.
- `<ts>.meta.json` per capture: upstream URL, HTTP status, ETag /
  Last-Modified, `poller_runs.id`. Lets Kotlin skip re-import on no-op
  fetches.
- Failed/partial fetches written as `<ts>.partial`, never linked.
- **Retention: keep all captures.** Crawling has real cost (Aspira's
  Azure WAF, Tesla's curl-impersonate + cookie refresh, OSM Overpass
  timeouts) — so once a capture is on disk, it stays. The Kotlin ETL
  must be replayable against the entire history without ever calling
  upstream. Disk grows on the order of MB/day; cheap relative to what
  one WAF strike costs.

**Kotlin ETL** lives in `backend/.../etl/`. Per-source classes (extending
the existing `Source` interface) own parsing, schema mapping, and
emitting `Poi` instances. Merging across sources (campgrounds = US +
BC-curated + AB-curated + Aspira) becomes a `fold` of those emitters —
the procedural Python merge stops existing.

**Deletes** stay mark-and-sweep, scoped per source set:

- Row absent from this run's emitted set → `deleted_at = NOW()`.
- Soft delete; `/api/pois` already filters `WHERE deleted_at IS NULL`.
- Reappearance un-deletes (`deleted_at = NULL` on next UPSERT).
- Tripwire: abort before sweep if `seen < 0.5 × prior_active` (already
  in `Importer.kt`).
- Sweep scope = the run's sources (e.g. campgrounds = the four sources
  above), never global. A campground feed going dark won't wipe Tesla.

**What deletes / collapses:**

- `fetch_parks_canada.py`, `fetch_aspira_bc_wa.py` — merge logic moves
  to `ParksCanadaSource.kt` + a new `AspiraIndexSource.kt`.
- The `aspira` block in curated `parks-canada-{bc,ab}.json` — those
  files revert to raw curated data (lat/lng + name + season); aspira
  IDs come from the actual aspira-maps fetch at ETL time.
- `data/*.geojson` (merged artifacts) — gone. Repo-curated JSON inputs
  move under `data/raw/` like everything else.
- `Phase.Fetch.Kotlin` from RFC 0004 step 3 — folds back into
  `Phase.Import.Kotlin`. Fetch is shell-only; ETL is Kotlin.

**Trade-offs.** ~300 LoC moves Python → Kotlin (mechanical; import path
already exists). Disk cost negligible. Loses ad-hoc `jq data/*.geojson`
introspection — replaced by `/api/pois`, which is what the FE sees
anyway.

### Migration: there isn't one

This is a personal project; treat as a full rewrite. Drop `pois` (and
the merged `data/*.geojson`) and rebuild from scratch against the new
schema. Captured raw data stays — the new Kotlin ETL replays it.

PR sequence (still useful for reviewability, not for back-compat):

1. **PR 1: Schema reset.** New migrations: `pois` reshaped per Section 4
   (sealed-type-aligned columns, `provider_ref`, `parent_poi_id`,
   `governing_body_id`, `last_poller_run_id`, `country`/`phone`/`address`/
   `info_url`/`last_verified` on the base, `reservable` dropped),
   plus `governing_body` + `booking_provider` dimension tables.
2. **PR 2: Thin Python fetchers + raw cache layout.** Existing scripts
   strip to fetch-only; `data/raw/<source>/<ts>.<ext>` becomes the only
   on-disk artifact. Captured raw is committed (or otherwise persisted)
   so the Kotlin ETL has something to replay against.
3. **PR 3: Sealed Kotlin Poi + ETL.** New `etl/` module reads
   `data/raw/<source>/latest/`, emits `Poi` instances, upserts with
   mark-and-sweep. `buildDrawerPayload` assembler.
4. **PR 4: Unified availability route.** `GET /api/availability/{poi_id}`
   dispatches by provider. Drawer payload references it.
5. **PR 5: Unified query API.** `POST /api/pois` accepts `types=` and
   replaces `/api/superchargers`. FE switches its fetch loop. SC GeoJSON
   file deletes.
6. **PR 6: Cleanup.** Remove `flattenPoi`, `campground-card.js`, dead
   per-layer fetch paths, and any vendor-specific FE code that survived.
7. **PR 7: Raw cache + Kotlin ETL (Section 7).** New `data/raw/<source>/`
   layout. One source at a time, in order of risk: Planet Fitness first
   (smallest, most isolated), then Tesla SC, then state/national parks
   (PAD-US — already minimal in Python), then campgrounds (the big merge).
   Each source's PR strips the corresponding Python transform and adds
   the equivalent Kotlin ETL. Mergeable independently because each source
   has its own poller target.

## Rationale

**Prior art: rec.gov + Aspira NextGen.** Both vendors model the same
domain with richer three-level hierarchies. Useful as a sanity check on
what we're not modeling.

| Layer | rec.gov RIDB | Aspira `/api/maps` | Us (proposed) |
|---|---|---|---|
| Umbrella | RecArea (forest, wilderness, corps lake) | Park (top-level node) | `Park` POI (polygon) |
| Container | Facility (campground/lookout/lodge, point) | MapLink / sub-area / loop (Banff: 7 loops) | `Campground` POI (point) |
| Bookable unit | Campsite (#A23, equipment-typed, attributes) | Resource (site, equipment-typed, per-night codes) | not modeled |
| Side dimensions | Permits, Tours, Activities, Events, Media | EquipmentCategory, BookingCategory, Amenity, Feature | flat string lists |

**What rec.gov + Aspira have that we don't:** site/resource level below
the campground, sub-area (loop) container within a park, facility/site
type taxonomies, equipment compatibility, first-class amenity + activity
join tables.

**What we explicitly skip (and why):**

- **Site-level modeling.** Heat strip aggregates *across* sites; we never
  need per-site state. Adding a 4th table doubles the schema for ~zero
  user-visible benefit. (See decision #11.)
- **Sub-area / loop modeling.** Natural fit for the `parent_poi_id` chain,
  but our curated JSONs don't carry it. Skip until per-loop UX is needed.
- **Equipment / amenity dimension tables.** FE doesn't filter on amenity;
  flat string array suffices.
- **Activities / events / tours / media.** rec.gov's secondary endpoints
  are no-ops for us — we link out, operator handles the rest.

**What it tells us to add later** (out of scope here, follow-up RFC):

- Facility type beyond `category='campground'` (tent / RV / mixed /
  backcountry / cabin). rec.gov's `Type` and Aspira's `BookingCategory`
  both expose this; we'd promote from JSONB once we have a sense of what
  comes through each source.

---

**Why BFF over rich client?** One FE consumer; the N-vendor × M-display-rule
explosion is already 60% of the drawer. BFF puts that in one place. If we
ever add a second consumer, reconsider (gRPC/protobuf likely wins).

**Why SC in `pois` and not its own table?** Same shape as everything else
(point + source + URL). Splitting duplicates the geometry/import-runs
infrastructure for no benefit.

**Why sealed Kotlin hierarchy?** Today's generic `PoiRow` + `properties:
JsonObject` leaks JSONB shape everywhere. Sealed types make
`buildDrawerPayload` exhaustive (compiler-checked).

**Why not GraphQL?** Doesn't pay back at one consumer + ~10 queries.

**Why one availability route?** Provider is BE-side knowledge. Existing
provider-specific routes leak it to the FE.

**Trade we're making vs. the current architecture:**

- BE gets fatter (display-rule logic, action assembly, vendor dispatch).
- FE gets thinner (pure rendering primitives + a `kind` switch).
- Schema changes to drawer payloads need coordinated deploys, but old
  clients tolerate new section kinds (skip-unknown).
- The `properties.type`-keyed renderer means a new type's first PR is just
  BE-side; FE can ship a fallback render until a custom one is added.

## Field audit: every property in `data/*.geojson` mapped to a home

Walking the actual columns we ingest today against the proposed model.
Anything that doesn't have a row gap-checks the design.

> **Caveat.** `data/*.geojson` are merged + transformed artifacts, not
> raw upstream — Python today already normalizes property bags, merges
> across sources, filters, and shapes for the FE's existing layer code.
> The audit below is a fair check on the BE-to-FE wire shape, not on
> what data exists upstream. The "Raw upstream" subsection at the end
> covers what we strip.

### `data/campgrounds.geojson`

| Field | Where it lives | Notes |
|---|---|---|
| `name` | `pois.name` | shared |
| `code` | `pois.source_id` | already does this |
| `category` (federal/state/local/provincial) | hoist to **`agency_kind` on `governing_body`** | the geojson `category` maps from `governing_body.kind` (already in the entity sketch) |
| `state` | `pois.region` | shared |
| `country` | promote to **`pois.country` (CHAR(2))** | not currently a column; matters for AB/BC dispatch and FE rendering |
| `phone` | `Campground.phone: String?` | per-type |
| `season` | `Campground.season: String?` | per-type |
| `sites` | `Campground.sites: Int?` | per-type |
| `amenities` | `Campground.amenities: List<String>` | per-type |
| `activities` | `Campground.activities: List<String>` | **missing from RFC**, add it (BC parks carries this) |
| `cell_coverage` | `Campground.cellCoverage: Map<Carrier, Pair<Float, Int>>?` | **missing from RFC**, rec.gov-enriched |
| `rating_reviews` | `Campground.ratingReviews: Pair<Float, Int>?` | **missing from RFC**, rec.gov-enriched |
| `photo_url` | `Campground.photoUrl: String?` | **missing from RFC**, but only present on BC parks; could promote to base if other types want it |
| `near` | `Campground.near: String?` | "5mi NE of X" — purely descriptive; per-type |
| `type` (NP/SP/PK/etc.) | drop | classifier shorthand from sources, redundant once `category` + `governing_body` exist |
| `typeLabel` ("Yoho National Park") | derived from parent POI | when `parent_poi_id` is wired, this is `parent.name` — drop the field |
| `parent_name`, `parent_type` | derived from `parent_poi_id` | drop after parent FK is populated |
| `recgov_id` | folds into `provider_ref.ids` | covered by Section 4 |
| `aspira` | folds into `provider_ref.ids` | covered |
| `parks_canada_url` / `parks_alberta_url` / `bcparks_url` | drop, replaced by **`pois.info_url`** | one nullable column for "non-booking park-info link"; the BE assembles "Park info on …" buttons from this + a label derived from `governing_body` |
| `reservable` | drop | per decision #9, season + provider state + live availability owns this |
| `enriched` | drop (poller-internal flag) | should never have left the importer; not user-visible |

### `data/national-parks.geojson` / `data/state-parks.geojson`

These come straight from PAD-US (USGS protected-areas dataset) with naming
conventions that smell of ESRI:

| Field | Where it lives | Notes |
|---|---|---|
| `Unit_Nm` | `pois.name` | shared |
| `Loc_Nm` | `Park.officialName: String?` (or drop) | often duplicates `Unit_Nm`; capture if non-empty |
| `State_Nm` | `pois.region` | shared |
| `Mang_Name` ("National Park Service") | `pois.governing_body_id` (FK) | exactly what `governing_body` is for |
| `Des_Tp` ("National Park", "Wilderness") | `Park.designation: String` | per-type — useful for FE labeling |
| `GIS_Acres` | `Park.acres: Double?` | per-type, already in RFC |

Geometry: PAD-US ships `Polygon` for state parks, `MultiPolygon` for many
national parks (Channel Islands, Glacier Bay). Already covered by the
`geom geometry(Geometry, 4326)` decision.

### `data/planet-fitness.geojson`

| Field | Where it lives | Notes |
|---|---|---|
| `name` | `pois.name` | shared |
| `osm_id` | `pois.source_id` (with `source='osm-pf'`) | shared |
| `street` / `city` / `state` / `postcode` | promote to **`pois.address` (JSONB) or split columns** | currently per-type-only on PF; tents and SCs also have these. JSONB blob keeps it open |
| `phone` | hoist to base or per-type? | campgrounds, PF, and SC service desks all have phones. Probably **base `pois.phone: String?`** |
| `opening_hours` | `PlanetFitness.openingHours: String?` | OSM-format (`"Mo-Fr 05:00-23:00"`); per-type for now, could go to base |
| `website` | `pois.info_url` | same column as the campground park-info URLs |

### `data/tesla-superchargers.geojson`

| Field | Where it lives | Notes |
|---|---|---|
| `name` ("Santa Barbara, CA") | `pois.name` | shared |
| `id` / `locationId` | `pois.source_id` | shared (with `source='tesla'`) |
| `street` / `city` / `state` / `country` | base `address` JSONB or columns | same case as PF |
| `status` ("OPEN" / "PERMIT") | filter at poller (only OPEN) — already done | shouldn't reach the row |
| `color` / `group` ("open" / "construction") | drop, derive from `status` | poller artifact |
| `facility` | `Supercharger.facility: String?` ("hotel parking") | per-type |
| `stallCount` | `Supercharger.stallCount: Int` | per-type, in RFC |
| `powerKilowatt` | `Supercharger.maxPowerKw: Int` | per-type, in RFC |

### Gaps the RFC closes

**Hoist to POI base** (was per-type or implicit):
`country` (drives US/CA dispatch), `phone` (3 of 4 types carry it),
`address` (JSONB blob), `info_url` (retires the
parks_canada_url / parks_alberta_url / bcparks_url / website sprawl —
BE composes "Park info on …" / "Visit website" buttons from `(info_url,
governing_body)`).

**Add to per-type shapes** (in geojson today, missing from RFC's first pass):
`Campground.photoUrl`, `activities`, `cellCoverage`, `ratingReviews`;
`Park.designation` (Des_Tp), `Park.officialName` (Loc_Nm); `PF.openingHours`;
`SC.facility`.

**Drop from the wire** (poller-internal or now-derivable):
`enriched`, `color`, `group`, `type`, `typeLabel`, `parent_name`,
`parent_type`, `reservable`.

### Raw upstream — what the geojsons strip

Each poller's raw upstream payload carries data the merged geojson drops.
None of this is forcing for the data model (JSONB accommodates it later);
flagged here so the future-fields audit isn't lost:

- **Tesla cua-api** (already cached at `data/pricing-cache/<id>.json`):
  `accessHours`, `accessType`, structured `address`,
  `amenities` (restroom/food/lodging), `isTrailerFriendly`,
  `openToNonTeslas`, `timeZone`, `commonSiteName`.
- **rec.gov RIDB:** facility `Description`, `Directions`, `Stay limit`,
  fees, photo URLs (we keep only `cell_coverage` + `rating_reviews`).
- **PAD-US:** ~30+ fields beyond the 5 we keep (`Pub_Access`, `Access_Typ`,
  `IUCN_Cat`, `Date_Est`, `Own_Type`).
- **Aspira `/api/maps`:** `mapImageUrls`, `description`, `directions`,
  `cancellationPolicy`, `arrivalInstructions`, sub-area names.
- **OSM Overpass (PF):** `wheelchair`, `level`, `payment:*`, social links.
- **uscampgrounds.info CSV:** `ELEV`, GPS-precise per-site coords,
  `AGENCY` (operator vs. manager).

**Worth capturing soon:** Tesla amenities + accessHours (in the cache
already, drawer popup gets richer for free); structured Tesla address
(country + centroid for free); rec.gov `Description`/`Directions` (the
drawer's expandable section is starved for non-recgov content).
**Skip:** Aspira sub-area names, Tesla `effectivePricebooks` (pricing
route owns it), most OSM tags (no use case).

> **Out of scope here.** Re-surveying each poller and updating the
> per-type Kotlin shapes is a follow-up task. RFC structure doesn't
> depend on which fields land.

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
| 8 | 2026-06-07 | Collapse `recgovId` + `aspira` into a generic `provider_ref` (booking_provider FK + opaque `ids` JSONB) | Per-vendor fields are exactly the N×M sprawl the RFC retires; opaque `ids` keeps the adapter contract local to its adapter |
| 9 | 2026-06-07 | Drop `reservable` from the row shape | It depends on season + provider state, not row-level data; the availability route owns the answer |
| 10 | 2026-06-07 | `created_at` / `updated_at` / `last_verified` are POI-level, not per-type | Lifecycle is shared; promoting `last_verified` from JSONB to a column makes it filterable + reusable for future types |
| 11 | 2026-06-07 | Skip rec.gov-style site-level (Campsite) modeling for now | Heat strip aggregates across sites; we never need per-site state. Revisit if we ever build per-site availability tracking |
| 12 | 2026-06-07 | Hoist `country`/`phone`/`address`/`info_url` to POI base | Field audit shows three of four POI types carry phone + address; one base `info_url` retires the parks_canada_url / parks_alberta_url / bcparks_url / website per-type sprawl |
| 13 | 2026-06-07 | Drop poller-internal fields from the wire (`enriched`, `color`, `group`, `type`, `typeLabel`, `parent_name`, `parent_type`) | Caller-derivable or only meaningful inside the poller; leaking them to the FE is incidental |
| 14 | 2026-06-07 | Python is fetch-only; Kotlin owns transform + upsert | Today's split has merge/transform logic in two languages. Python writes raw bytes to `data/raw/<source>/<ts>.<ext>`; Kotlin ETL parses, transforms, merges, enriches, upserts. One language for the schema-shaped boundary. |
| 15 | 2026-06-07 | Raw cache is timestamped, `latest` symlink is what Kotlin reads, **all captures retained** | Crawling upstream is expensive (Aspira WAF, Tesla curl-impersonate + cookie injection); raw replay is free. ETL must always be replayable against historical captures with zero upstream calls |
| 17 | 2026-06-07 | Treat the new data model as a full rewrite, not a migration | Personal project, no live users to preserve continuity for. Drop `pois` + merged geojsons, rebuild from raw captures. Saves ~50% of the design-complexity budget that would otherwise be spent on dual-read/dual-write transitions |
| 16 | 2026-06-07 | Kotlin ETL handles deletes via mark-and-sweep, scoped per source | Existing `Importer.kt` behavior generalizes: rows not seen in a run get `deleted_at`; reappearance un-deletes; tripwire (seen < 0.5 × prior) aborts before sweep. Sweep scope is the run's source set, not all `pois` |
