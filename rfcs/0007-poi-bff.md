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

**Considered during plan-eng-review and explicitly deferred:**

- **Site/Resource-level modeling (rec.gov Campsite, Aspira Resource).** Heat
  strip aggregates across sites; we don't run our own booking flow.
- **Sub-area / loop modeling within a park.** Aspira has it (Banff:
  7 loops); we don't, and per-loop UX isn't planned.
- **Equipment / amenity dimension tables.** FE doesn't filter on
  amenity; flat string list suffices.
- **Activities / events / tours / media** (rec.gov RIDB secondaries).
  We link out, vendor handles. No use case.
- **Per-type cap behavior on `POST /api/pois`.** Single global cap
  for first-pass; revisit if any type starves on dense queries (Bay
  Area SC density, etc.).
- **Search index for global pin name search (issue #67).** Unified
  `/api/pois?q=` solves it server-side; whether to debounce or build
  a separate `/api/pois/search-index` boot payload is a follow-up.
- **Drawer payload schema versioning.** Skip-unknown on new section
  kinds is enough today (FE+BE deploy together). Add `schema_version`
  if a non-additive change ever ships.
- **Promoting `Pricing` to a first-class capability** (`features.pricing.endpoint`).
  Requires retrofitting `/api/pricing/{slug}` to key on `(source,
  source_id)`. Independent of this RFC.
- **Sub-day fetch caching at the meta.json layer** (skip re-import
  on no-op fetches via ETag/Last-Modified match). The envelope carries
  the headers; using them is a perf optimization deferred until proven
  needed.

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
                        │  (parent: derived│
                        │   via ST_Within, │
                        │   not stored)    │←┐
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
- **POI → POI (parent)** is a derived relation, not a stored FK. The
  drawer payload assembler runs `ST_Within(child.geom, parent.geom) AND
  parent.category IN ('national-park', 'state-park')` at query time
  (sub-ms at 11K rows with the existing GIST index). Subtitles
  ("Kicking Horse Campground · Yoho National Park") read from the
  spatial join. We deliberately do not store a `parent_poi_id` column
  — the geometry is the truth, a denormalized FK would silently drift
  when PAD-US re-issues park polygons. (Correctness over perf — see
  the project memory rule on the same topic.)
- **POI → source** (N:1, required, already modeled as `source TEXT`).
  Eventually move to a real FK once we promote `source` to a table.

`governing_body` and `booking_provider` are small dimension tables (10–20
rows each) — promote-from-string, not free-form JSONB. Display strings
that the BE renders (e.g. "Booking via Aspira NextGen (BC Parks)") read
from those tables instead of being hardcoded in TypeScript or the
drawer assembler.

**`booking_provider` shape**:

```
id  | name                          | vendor   | host                          | adapter_class
----|-------------------------------|----------|-------------------------------|---------------------
1   | Recreation.gov                | recgov   | www.recreation.gov            | RecGovAdapter
2   | Aspira NextGen (Parks Canada) | aspira   | reservation.pc.gc.ca          | AspiraAdapter
3   | Aspira NextGen (BC Parks)     | aspira   | camping.bcparks.ca            | AspiraAdapter
4   | Aspira NextGen (WA State)     | aspira   | washington.goingtocamp.com    | AspiraAdapter
5   | Camis (Alberta Parks)         | camis    | reserve.albertaparks.ca       | (future)
```

One row per (vendor × host). Aspira gets 3 rows because the same
adapter speaks to 3 different host endpoints — the route handler reads
`provider.host` to know where to call. `governing_body` and
`booking_provider` are independent: NPS (governing) → rec.gov (booking),
BC Parks (governing) → Aspira BC (booking).

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

**`ProviderRef`** is a sealed hierarchy mirroring the booking_provider
dimension. Same reasoning that picks sealed Poi over generic
`Poi + properties:JsonObject` applies one level deeper — the compiler
enforces "Aspira has mapId, RecGov has recgov_id":

```kotlin
sealed class ProviderRef {
    data class RecGov(val recgovId: String) : ProviderRef()
    data class Aspira(
        // host comes from the FK row in booking_provider, not duplicated here.
        // Aspira gets 3 rows in booking_provider — one per host (PC/BC/WA) —
        // and the POI row's `booking_provider_id` FK selects which.
        val transactionLocationId: Long,
        val mapId: Long,
        val resourceLocationId: Long?
    ) : ProviderRef()
    data class Camis(val facilityId: String) : ProviderRef()
}
```

The POI row carries both `booking_provider_id` (FK) and `provider_ref`
(JSONB sealed). The adapter dispatcher reads both — the FK selects the
adapter row (which carries the host), the `provider_ref` carries the
typed IDs.

Stored as JSONB on the row, payload-only (no discriminator — `booking_provider_id` is the single source of truth for which adapter):

```json
// rec.gov  (booking_provider_id=1)
{"recgov_id": "232857"}
// Aspira NextGen for BC Parks  (booking_provider_id=3, host comes from that row)
{"transactionLocationId": -2147483630, "mapId": -2147483388, "resourceLocationId": -2147483624}
// Camis  (booking_provider_id=5)
{"facility_id": "BVPP"}
```

Dispatch: `booking_provider.vendor` selects the adapter, the adapter
parses `provider_ref` JSONB into its own sealed variant. No
`JsonObject.getString("foo")` outside the adapter.

```kotlin
val provider = bookingProvider.findById(poi.bookingProviderId) ?: return noProvider()
val adapter = adapters.getValue(provider.vendor)         // RecGovAdapter / AspiraAdapter / ...
val ref     = adapter.parseRef(poi.providerRefJson)       // type-narrowed via reified generics
adapter.availability(ref, days)
```

Adding a new provider is: new sealed variant + new adapter + new row in
`booking_provider`. The compiler refuses to forget the new variant in
`buildDrawerPayload` or the dispatcher.

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
2. Reads its `provider_ref` (sealed; see Section 4).
3. Dispatches on the sealed type to the matching adapter.
4. Adapter answers (or returns `no_provider` if `provider_ref IS NULL`),
   normalized to one per-day status response shape.

The drawer payload's `availability.endpoint` always points here — FE
doesn't know which provider answered.

**Adapter interface:**

```kotlin
interface BookingProviderAdapter<R : ProviderRef> {
    val providerId: Long                    // FK row in booking_provider
    val refClass: KClass<R>                 // for dispatch

    /** 30-day availability for one POI. Adapter owns throttling, auth,
     *  cookies, WAF-evasion (browser UA, header shape, etc.). */
    suspend fun availability(ref: R, days: Int): AvailabilityResult

    /** External booking URL the drawer's primary action links to. */
    fun deeplink(ref: R, host: String? = null): String

    /** Optional poller hook: this provider's `/api/maps`-equivalent endpoint
     *  produces the per-park IDs the curated POI rows reference. Returns
     *  the indexed payload; null if the provider doesn't expose one. */
    suspend fun fetchIndex(): RawCapture?     // wraps in the envelope
}
```

Each adapter owns:

- **Throttle state** (today's `AspiraAvailabilityClient` mutex moves
  inside the adapter — one mutex per provider singleton, not global).
- **Auth / cookie / UA shape** (Tesla's curl-impersonate stays here;
  Aspira's no-Accept-Charset trick stays here).
- **Host whitelist** if multi-host (Aspira: PC / BC / WA share the
  adapter; the ALLOWED_HOSTS set lives on the adapter, not in the
  route handler).
- **Provider-specific status code mapping** (`AspiraStatus.classify`
  is the adapter's, not shared).

Adding a new provider is one new sealed `ProviderRef.Foo` variant + one
new `FooAdapter : BookingProviderAdapter<ProviderRef.Foo>` + one row in
`booking_provider`. The compiler refuses to forget the new variant in
the route's exhaustive `when`. No new API route, no new POI field, no
FE change.

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

- `YYYY-MM-DDTHH-MM-SSZ.json` per capture (UTC, `-` for `:` for FS-safety).
  Multi-file sources nest under a per-capture directory.
- **No `latest` symlink.** The filename format is monotonic; ETL picks
  the newest by listing the directory and taking the lexicographically-
  greatest entry. Atomic via the OS rename. One less moving part.
- ETL accepts an optional `--at <ts>` (or per-source `--filename
  <ts>.<ext>`) for replay — same directory, different file.
- Failed/partial fetches written as `.partial`, never picked up.

**Capture envelope: Python writes structured, not verbatim.**

A bare HTTP body strips the contract: which endpoint produced this,
under what headers, by which fetcher version. Python wraps every
response in a uniform envelope so Kotlin always reads the same outer
shape, then dispatches the inner `payload` to the per-source DTO:

```json
{
  "fetcher": "fetch_aspira_index",
  "fetcher_version": "1",
  "fetched_at": "2026-06-07T19:23:44Z",
  "request":  { "url": "https://reservation.pc.gc.ca/api/maps",
                "method": "GET",
                "headers": { "User-Agent": "...", "Referer": "..." } },
  "response": { "status": 200,
                "headers": { "content-type": "application/json",
                             "etag": "..." } },
  "poller_run_id": 123,
  "payload": <verbatim upstream JSON | string for non-JSON | base64 if binary>
}
```

Trade vs. fully-verbatim: 200 bytes of envelope per capture, in
exchange for: (a) the ETL never has to infer source from path, (b)
when a vendor renames `/api/maps` to `/api/v2/maps` we have a record
of which captures hold the old contract, (c) one file per capture
(no separate `<ts>.meta.json` to keep in sync).
- **Retention: keep all captures.** Crawling has real cost (Aspira's
  Azure WAF, Tesla's curl-impersonate + cookie refresh, OSM Overpass
  timeouts) — so once a capture is on disk, it stays. The Kotlin ETL
  must be replayable against the entire history without ever calling
  upstream. Disk grows on the order of MB/day; cheap relative to what
  one WAF strike costs.

**Kotlin ETL** lives in `backend/.../etl/`. The pipeline is staged so each
step is a pure function with its own test surface, not one
`Importer.run` mega-loop:

```
data/raw/<source>/<ts>.json                    (envelope on disk)
        │
        │  read + parse envelope (uniform)
        ▼
   <Source>RawDto                              (per-source data class
        │                                       mirroring upstream wire shape;
        │                                       deserialize is the only thing
        │                                       this stage does)
        │
        │  validate (per-source rules:
        │            required fields, enum membership,
        │            geometry well-formedness, ID format)
        ▼
   ValidatedDto OR ValidationError             (ValidationError counted in
        │                                       poller_runs.counts; row dropped,
        │                                       run continues)
        │
        │  transform (DTO → domain Poi sealed type
        │             from Section 4; lookup
        │             governing_body / booking_provider FKs)
        ▼
     Poi                                       (canonical in-memory model)
        │
        │  merge (per-target; e.g. campgrounds = fold(US, BC, AB, Aspira))
        ▼
   List<Poi>                                   (deduplicated by source_id within
        │                                       the target's source set)
        │
        │  upsert (mark-and-sweep against pois.source IN
        │          {target's sources}; tripwire on 50% shrinkage)
        ▼
     pois rows                                 (deletes are scoped per source)
```

What each stage owns:

- **Parse** — read envelope, deserialize `payload` into a per-source
  Kotlin data class (`UsCampgroundsRawDto`, `AspiraMapsRawDto`, etc.).
  Pure. No DB. No domain types.
- **Validate** — per-source rules: required fields, enum membership,
  geometry well-formedness, ID format. Bad rows produce
  `ValidationError(row, reason)`, counted but not fatal. Pure.
- **Transform** — DTO → `Poi` sealed type. Lookup `governing_body_id`
  + `booking_provider_id` from the dimension tables (read-only). Pure
  except for the dim-table reads.
- **Merge** — per-target fold (campgrounds = US + BC + AB + Aspira).
  Dedupe by `(source, source_id)`. Pure.
- **Upsert** — mark-and-sweep against `pois.source IN {target's
  sources}`. Tripwire if `seen < 0.5 × prior_active` aborts before
  sweep. The only stage that touches DB write.

Why staged:

1. Each stage is unit-testable without a DB. Parse + Validate +
   Transform have golden-file tests against `data/raw/<source>/`
   fixtures — captured once, replayed forever.
2. Validation errors are counted in `poller_runs.counts` and the
   ingest dashboard surfaces them — bad upstream data doesn't
   silently poison `pois`.
3. Transform is reusable. A future CLI exporter or search-index
   builder consumes `Poi` instances, not DB rows.

**Code organization.** One file per source (`etl/aspira/AspiraEtl.kt`,
`etl/uscampgrounds/UsCampgroundsEtl.kt`, etc.) contains that source's
DTO + validator + transformer + the orchestrating function. The contract
is an abstract interface every source implements:

```kotlin
interface SourceEtl<DTO : Any, OUT : Poi> {
    val sourceName: String                  // 'aspira-maps', 'uscampgrounds', ...
    fun parse(envelope: Envelope): DTO       // raw JSON -> DTO
    fun validate(dto: DTO): ValidationResult // ok | List<ValidationError>
    fun transform(dto: DTO, ctx: TransformCtx): List<OUT>
}
```

The interface gives a uniform shape to grep across (`SourceEtl<*, *>`),
the per-source file keeps the cohesive mass — DTO, validator, transformer,
and tests against captured raw fixtures all in one place.

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
  files keep an explicit `aspira_park_title` (the verbatim Aspira map
  title, e.g. `"Yoho"`). At ETL time, the binding is an exact-match
  lookup in the aspira-maps fetch — no fuzzy name normalization. If
  Aspira renames a park, the binding fails loud (counted in the
  poller_run; surfaces in the ingest dashboard) instead of silently
  dropping the heat strip. The IDs themselves still come from the
  aspira-maps fetch, so they re-stamp on each run.
- `data/*.geojson` (merged artifacts) — gone. Repo-curated JSON inputs
  move under `data/raw/` like everything else.
- `Phase.Fetch.Kotlin` from RFC 0004 step 3 — folds back into
  `Phase.Import.Kotlin`. Fetch is shell-only; ETL is Kotlin.

**Trade-offs.** ~300 LoC moves Python → Kotlin (mechanical; import path
already exists). Disk cost negligible. Loses ad-hoc `jq data/*.geojson`
introspection — replaced by `/api/pois`, which is what the FE sees
anyway.

### Test plan

This RFC introduces enough new surface that the test posture is part
of the contract. Coverage targets, by stage:

```
ETL PIPELINE                                              COVERAGE
[+] Envelope.parse(json)                                  ★★★ schema regression test
[+] <Source>Etl.parse(envelope) → DTO (per source)        ★★★ golden-file per source
                                                              fixtures captured from
                                                              real raw/<source>/<ts>.json
[+] <Source>Etl.validate(DTO) → ok | errors               ★★★ happy + each error class
                                                              (missing required, bad enum,
                                                               malformed geom, bad ID format)
[+] <Source>Etl.transform(DTO, ctx) → List<Poi>           ★★★ each per-type field path,
                                                              missing-optional-becomes-null,
                                                              MultiPolygon round-trip,
                                                              governing_body lookup miss
[+] ETLOrchestrator.merge(per-source streams)             ★★  dedupe by (source, source_id),
                                                              order-independence
[+] Importer.upsert(List<Poi>)                            ★★★ mark-and-sweep semantics,
                                                              tripwire fires at <50%,
                                                              resurrected rows un-delete

API LAYER
[+] POST /api/pois bbox/corridor/types/q                  ★★★ each filter + their combos,
                                                              mixed-type response shape,
                                                              limit cap behavior
[+] GET /api/availability/{poi_id}                        ★★★ no provider → no_provider state,
                                                              dispatch by booking_provider_id,
                                                              adapter exception → upstream_5xx,
                                                              cache hit/miss behavior
[+] GET /api/pois/{id}/drawer                             ★★★ section tree per POI type,
                                                              actions[] composition,
                                                              availability.endpoint reference,
                                                              campground subtitle from spatial parent

ADAPTERS                                                  COVERAGE
[+] RecGovAdapter (parseRef + availability + deeplink)    ★★★ MockEngine; golden raw fixtures
[+] AspiraAdapter (parseRef + availability + deeplink)    ★★★ + WAF-trip retry, all 3 hosts
[+] AspiraStatus.classify                                 ★★★ already covered ✓

FE DRAWER
[+] renderSection(kind=availability)                      ★★  strip render + skeleton/error
[+] renderSection(kind=actions)                           ★★  button list + external links
[+] renderSection(kind=details)                           ★★  expandable + pill rows
[+] renderSection(unknown kind)                           ★★  silent skip
[→E2E] full drawer flow per POI type                      ★★★ campground/SC/park click → drawer

LEGEND: ★★★ behavior + edge + error  ★★ happy path  ★ smoke
```

Test strategy:

- **Captured raw fixtures.** Each source's first ETL test PR copies
  one real capture from `data/raw/<source>/` into the test resources
  directory. Subsequent runs replay against the fixture; no mocked
  HTTP, no Akamai/WAF surprise. The fixture is the contract.
- **Adapter mocks** use Ktor MockEngine (already in use for
  AspiraAvailabilityClient) — adapter tests are unit, no real network.
- **Drawer payload contract tests** assert the section tree shape per
  POI type. Snapshot-style is fine: render `buildDrawerPayload(poi)`,
  compare against a golden JSON. New section types breaking old
  clients gets caught here.
- **E2E** extends `SmokeTest.kt`: click campground → assert heat
  strip renders, click SC → assert SC popup, click park → assert
  drawer renders without errors. Three new test cases.
- **Regression coverage** is a hard requirement: any path the existing
  drawer/FE code covers must have an equivalent test against the new
  payload-driven path before the corresponding old code deletes.

### Migration: there isn't one

This is a personal project; treat as a full rewrite. Drop `pois` (and
the merged `data/*.geojson`) and rebuild from scratch against the new
schema. Captured raw data stays — the new Kotlin ETL replays it.

PR sequence (each independently reviewable):

1. **PR 1: Schema reset.** New migrations: drop `pois`; recreate with
   the new shape per Section 4 (sealed-type-aligned columns,
   `provider_ref`, `booking_provider_id`, `governing_body_id`,
   `last_poller_run_id`, `country`/`phone`/`address`/`info_url`/
   `last_verified` on the base, `reservable` dropped). Add
   `governing_body` + `booking_provider` dimension tables, seeded.
2. **PR 2: Thin Python fetchers + envelope-wrapped raw cache.**
   Existing scripts strip to fetch-only and write
   `data/raw/<source>/<ts>.json` envelopes. Captured raw committed (or
   otherwise persisted) so the Kotlin ETL has something to replay.
3. **PR 3: Sealed Kotlin Poi + staged ETL.** New `etl/` module: per-source
   `SourceEtl` impls (parse → validate → transform), orchestrator (merge
   → upsert), each with golden-file unit tests against captured fixtures.
4. **PR 4: Adapter framework + unified availability route.**
   `BookingProviderAdapter` interface, `RecGovAdapter` + `AspiraAdapter`
   implementations, `GET /api/availability/{poi_id}` dispatcher. Adapter
   contract tests for each. Old per-vendor routes stay alive but unused.
5. **PR 5: Drawer payload assembler + `GET /api/pois/{id}/drawer`.**
   `buildDrawerPayload` pattern-matches on sealed Poi. Per-type contract
   tests verifying section tree shape.
6. **PR 6: Unified `POST /api/pois` (bbox/corridor/types/q).** Replaces
   `/api/superchargers` and absorbs the campsite availability flow's
   fetch shape.
7. **PR 7: FE consolidation.** Drawer renders from payload tree.
   `flattenPoi`, `campground-card.js`, vendor branches, per-layer
   fetch paths all delete. Map gets one point layer + one polygon layer
   driven by `properties.type`.
8. **PR 8: Cleanup of old routes and old `pois` infrastructure.**
   Remove `/api/campsite/availability/*`, `/api/superchargers`, the
   merged `data/*.geojson` files (they're regenerated as needed for
   debug), `Phase.Fetch.Kotlin` from RFC 0004.

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
- **Sub-area / loop modeling.** Would need a parent relationship (FK or
  spatial), and our curated JSONs don't carry per-loop polygons. Skip
  until per-loop UX is needed.
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
| `typeLabel` ("Yoho National Park") | derived from spatial parent at drawer-payload time (`ST_Within` lookup) | drop the column |
| `parent_name`, `parent_type` | derived from spatial parent | drop the columns |
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
- **Mixed-type cap behavior on `POST /api/pois`.** With a 2000-row cap
  and types=campground,supercharger&bbox=..., one type can starve the
  other (Bay Area is dense in SC; query returns zero campgrounds).
  Punted — first-pass implementation uses a single global cap, and we
  iterate only if the FE actually starves. Three options on the table:
  per-type cap (UNION ALL of LIMITed selects), stratified sample,
  zoom-driven only.
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
| 6 | 2026-06-07 | Promote `governing_body` and `booking_provider` to dimension tables | Captures the agency-vs-source distinction (rec.gov serves multiple agencies). Parent-of relationship stays derived via ST_Within (decision #18 supersedes the original parent_poi_id self-FK) |
| 18 | 2026-06-07 | Drop `parent_poi_id` self-FK; compute parent-of via ST_Within at query time | Spatial geometry is the truth; storing a derived FK creates a second source that drifts when PAD-US re-issues park polygons. Sub-ms at 11K rows. Correctness over perf |
| 7 | 2026-06-07 | Rename "ingest" to "poller" in this RFC's vocabulary | "poller" describes the periodic, schedule-driven nature of the work better than "ingest"; existing artifacts (`ingest_runs` table, `IngestController`, `/api/admin/ingest/*`) rename in a separate mechanical PR |
| 8 | 2026-06-07 | Collapse `recgovId` + `aspira` into a generic `provider_ref` (booking_provider FK + opaque `ids` JSONB) | Per-vendor fields are exactly the N×M sprawl the RFC retires; opaque `ids` keeps the adapter contract local to its adapter |
| 9 | 2026-06-07 | Drop `reservable` from the row shape | It depends on season + provider state, not row-level data; the availability route owns the answer |
| 10 | 2026-06-07 | `created_at` / `updated_at` / `last_verified` are POI-level, not per-type | Lifecycle is shared; promoting `last_verified` from JSONB to a column makes it filterable + reusable for future types |
| 11 | 2026-06-07 | Skip rec.gov-style site-level (Campsite) modeling for now | Heat strip aggregates across sites; we never need per-site state. Revisit if we ever build per-site availability tracking |
| 12 | 2026-06-07 | Hoist `country`/`phone`/`address`/`info_url` to POI base | Field audit shows three of four POI types carry phone + address; one base `info_url` retires the parks_canada_url / parks_alberta_url / bcparks_url / website per-type sprawl |
| 13 | 2026-06-07 | Drop poller-internal fields from the wire (`enriched`, `color`, `group`, `type`, `typeLabel`, `parent_name`, `parent_type`) | Caller-derivable or only meaningful inside the poller; leaking them to the FE is incidental |
| 14 | 2026-06-07 | Python is fetch-only; Kotlin owns transform + upsert | Today's split has merge/transform logic in two languages. Python writes raw bytes to `data/raw/<source>/<ts>.<ext>`; Kotlin ETL parses, transforms, merges, enriches, upserts. One language for the schema-shaped boundary. |
| 15 | 2026-06-07 | Raw cache is timestamped (`YYYY-MM-DDTHH-MM-SSZ.<ext>`); ETL picks newest by lexical sort. Optional `--filename` for replay. **All captures retained.** | Filename format is monotonic, so no symlink needed (one less moving part). Crawling upstream is expensive (Aspira WAF, Tesla curl-impersonate + cookie injection); raw replay is free. ETL must always be replayable against historical captures with zero upstream calls |
| 17 | 2026-06-07 | Treat the new data model as a full rewrite, not a migration | Personal project, no live users to preserve continuity for. Drop `pois` + merged geojsons, rebuild from raw captures. Saves ~50% of the design-complexity budget that would otherwise be spent on dual-read/dual-write transitions |
| 19 | 2026-06-07 | Python writes envelope-wrapped raw, not bare bytes | Bare HTTP body strips the contract (which endpoint, what headers, which fetcher version). Envelope is uniform; ETL parses outer shape once, dispatches inner payload by `fetcher` field. Replaces the separate `<ts>.meta.json` sidecar |
| 20 | 2026-06-07 | ETL is staged: Parse → Validate → Transform → Merge → Upsert | Each stage is pure (except Upsert), unit-testable against captured raw fixtures with no DB. Validation errors counted in poller_runs.counts; bad upstream data doesn't poison pois. Transform-stage `Poi` instances are reusable for future consumers (CLI exporter, search index) |
| 16 | 2026-06-07 | Kotlin ETL handles deletes via mark-and-sweep, scoped per source | Existing `Importer.kt` behavior generalizes: rows not seen in a run get `deleted_at`; reappearance un-deletes; tripwire (seen < 0.5 × prior) aborts before sweep. Sweep scope is the run's source set, not all `pois` |
| 21 | 2026-06-07 | ETL binds aspira IDs by explicit `aspira_park_title` exact match, not fuzzy normalize | Curated AB/BC JSON files carry the verbatim Aspira map title. Rename detection becomes loud (poller_run validation error), not silent (heat strip drops). Brittle work moves to commit-time |
| 22 | 2026-06-07 | Sealed `ProviderRef` hierarchy (RecGov / Aspira / Camis), not opaque `ids: JsonObject` | Same reasoning that makes us pick sealed Poi over generic Poi+JsonObject applies one level deeper. Compiler-checked variants; adapters receive typed values |
| 23 | 2026-06-07 | Aspira `host` lives on `booking_provider` row, not on `ProviderRef.Aspira` | Aspira gets 3 rows (PC/BC/WA), each with its host. One source of truth; no per-row redundancy |
| 24 | 2026-06-07 | Drop `kind` discriminator from `provider_ref` JSONB; dispatch via `booking_provider_id` FK | Single source of truth for which adapter handles a row; FK and JSONB discriminator can't disagree |
| 25 | 2026-06-07 | Pin `BookingProviderAdapter<R : ProviderRef>` interface in the RFC | Makes throttle / auth / cookie / host-whitelist ownership explicit; route handler stays minimal |
| 26 | 2026-06-07 | One file per source under `etl/<source>/`, behind a uniform `SourceEtl<DTO, OUT>` interface | Cohesive grouping per source; uniform contract for parse/validate/transform |
| 27 | 2026-06-07 | Full-coverage test posture: golden-file ETL + adapter unit + drawer-payload contract + E2E per type | AI-assisted coding makes complete suites cheap; matches the project's stated 'too many tests > too few' preference |

## Implementation Tasks

Synthesized from this review's findings. Each task derives from a specific
finding above. Run with Claude Code or Codex; checkbox as you ship.

- [ ] **T1 (P1, human: ~30min / CC: ~10min)** — curated PC JSON — Add `aspira_park_title` field to every entry in `data/parks-canada-{bc,ab}.json`, verbatim Aspira title
  - Surfaced by: Architecture #1 — fuzzy match at ETL silently drops bindings on rename
  - Files: `data/parks-canada-bc.json`, `data/parks-canada-ab.json`
  - Verify: every entry has `aspira_park_title`; manual cross-check against `https://reservation.pc.gc.ca/api/maps`
- [ ] **T2 (P1, human: ~6h / CC: ~1.5h)** — Python fetchers — Rewrite all `scripts/fetch_*.py` as envelope-wrapped raw-only writers
  - Surfaced by: Architecture #5 (envelope), Architecture #6 (multi-stage). Decision #14, #19.
  - Files: `scripts/fetch_*.py` (all), new shared `scripts/_fetch_envelope.py` helper
  - Verify: each script writes `data/raw/<source>/<ts>.json` with envelope shape; no transform logic remaining
- [ ] **T3 (P1, human: ~2d / CC: ~3h)** — Kotlin ETL — Build `backend/.../etl/` module with per-source `SourceEtl` impls (Parse/Validate/Transform) + orchestrator (Merge/Upsert)
  - Surfaced by: Architecture #6, Code Quality #11. Decision #20, #26.
  - Files: `backend/src/main/kotlin/ca/floo/roadtrip/etl/{aspira,uscampgrounds,padus,osm-pf,tesla}/`
  - Verify: golden-file unit tests against `data/raw/<source>/` fixtures pass; tripwire fires at <50% shrinkage
- [ ] **T4 (P1, human: ~3h / CC: ~30min)** — Schema reset — Drop `pois`, recreate with new shape (sealed-aligned, `provider_ref`, `booking_provider_id`, `governing_body_id`, base columns, `last_verified`)
  - Surfaced by: Section 4, Section 7. Decision #17, #12, #18.
  - Files: `backend/src/main/resources/db/migration/V5__pois_v2.sql` (drop+recreate), seed `governing_body` + `booking_provider`
  - Verify: Flyway migration runs cleanly; jOOQ codegen produces expected types
- [ ] **T5 (P1, human: ~4h / CC: ~1h)** — `BookingProviderAdapter` interface + impls — Pin interface, port `RecGovAdapter` + `AspiraAdapter` (one adapter, 3 hosts)
  - Surfaced by: Code Quality #7. Decision #25, #22.
  - Files: `backend/.../availability/BookingProviderAdapter.kt`, `RecGovAdapter.kt`, `AspiraAdapter.kt`
  - Verify: `AspiraStatus.classify` tests still pass; new MockEngine tests for each adapter's `parseRef` + `availability` + `deeplink`
- [ ] **T6 (P1, human: ~3h / CC: ~30min)** — `GET /api/availability/{poi_id}` — Unified dispatch route
  - Surfaced by: Section 5. Decision #4.
  - Files: `backend/.../api/AvailabilityRoutes.kt`
  - Verify: per-provider integration tests; `no_provider` for unbound POIs; adapter-throws fall-through to `upstream_5xx`
- [ ] **T7 (P1, human: ~5h / CC: ~1h)** — Sealed `Poi` + `buildDrawerPayload` + `GET /api/pois/{id}/drawer`
  - Surfaced by: Section 1, Section 4. Decision #1, #3.
  - Files: `backend/.../poi/Poi.kt`, `DrawerPayload.kt`, `api/PoiDrawerRoutes.kt`
  - Verify: contract test per POI type; spatial-parent subtitle test for campgrounds inside parks
- [ ] **T8 (P2, human: ~4h / CC: ~1h)** — Unified `POST /api/pois` (bbox + corridor + types + q)
  - Surfaced by: Section 3.
  - Files: `backend/.../api/PoiRoutes.kt`
  - Verify: existing PoiRoutesTest passes; new tests for `types=` filter, `q=` search
- [ ] **T9 (P2, human: ~6h / CC: ~1.5h)** — FE consolidation — Drawer renders from payload tree; kill flatten/card/vendor branches
  - Surfaced by: Section 6.
  - Files: `web/drawer.js`, `web/app.js`; delete `web/campground-card.js`, `web/superchargers.js`
  - Verify: SmokeTest + new E2E for campground/SC/park click → drawer
- [ ] **T10 (P3, human: ~1h / CC: ~10min)** — Rename `ingest` → `poller` across existing artifacts
  - Surfaced by: Decision #7 (out of scope for this RFC, follow-up)
  - Files: `ingest_runs` → `poller_runs`, `IngestController` → `PollerController`, `/api/admin/ingest/*` → `/api/admin/poller/*`
  - Verify: existing `IngestControllerTest` ports cleanly

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | not run |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | not run |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 13 issues raised, 11 resolved with user decisions, 1 verified-acceptable, 1 deferred |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | not run |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | not run |

**UNRESOLVED:** mixed-type cap behavior on `POST /api/pois` (decision #28 — punted to first-pass implementation; revisit if any type starves).

**VERDICT:** ENG CLEARED — ready to implement. CEO/Design reviews not run; not required for this RFC (architectural + data-model scope, no UI/strategic decisions left open).

