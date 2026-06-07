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

jOOQ codegen owns the shared fields; per-type properties parse from JSONB
explicitly. The `Poi` -> drawer payload assembly is a `fun
buildDrawerPayload(poi: Poi): DrawerPayload` that pattern-matches on the
sealed type — one place for all display-rule logic.

Lifecycle fields (`created_at`, `updated_at`, `last_verified`) are
POI-level, not per-type. The schema already has the first two; add
`last_verified DATE` to the row (currently it lives in JSONB on
campgrounds — a curated audit field set by the BC/PC scripts when someone
manually checks a pin). Promoting to a column makes it filterable
(`WHERE last_verified < now() - 60 days`) and stops it being campground-only
prior art for the next type that wants it.

`ProviderRef` is the per-POI ID payload that the booking-provider's adapter
needs to look the POI up upstream. It's deliberately generic — instead of
campground-specific `recgovId: String?` and `aspira: AspiraIds?` fields, we
carry one nullable `providerRef` that pairs the FK to `booking_provider`
with whatever ID(s) that provider needs.

```kotlin
data class ProviderRef(
    val providerId: Long,    // FK to booking_provider
    val ids: JsonObject      // shape determined by the provider's adapter
)
```

Examples (the `ids` schema is the adapter's contract):

```json
// rec.gov
{"providerId": 1, "ids": {"recgov_id": "232857"}}

// Aspira NextGen (Parks Canada / BC / WA share this shape)
{"providerId": 2, "ids": {"transactionLocationId": -2147483630, "mapId": -2147483388, "resourceLocationId": -2147483624}}

// Camis (Alberta), if/when added
{"providerId": 3, "ids": {"facility_id": "BVPP"}}
```

Stored on the row as a JSONB column (`provider_ref`) — promoting the inner
`ids` to columns would require a per-provider table per POI type, which is
exactly the kind of N×M sprawl this RFC is trying to retire. Validation
(shape of `ids` matching the named provider) lives in the adapter; bad data
fails at adapter-load time, not at row insert.

The vendor-dispatch availability route (Section 5) reads `providerRef`,
looks up the matching `BookingProviderAdapter`, and hands the adapter the
opaque `ids` blob. No campground-specific code outside the adapter knows
what's inside.

#### `reservable` is not a row-level fact

The current schema carries `reservable: Boolean?` on each campground row.
Treating it as static is wrong: a campground is reservable in summer but
not in winter; rec.gov sometimes lifts a site from FCFS to reservable
mid-season. Three signals that drive the answer:

1. **Today vs `season`** — if today is outside the season window, "Closed
   for season" wins, no booking flow.
2. **`providerRef` presence** — without one, the pin is FCFS / not on a
   booking platform.
3. **Live availability** — provider may temporarily disable bookings.

The right place for the answer is the availability route. The drawer
payload's `availability` section returns a normalized status (`available`
/ `partial` / `closed_for_season` / `no_provider` / `unreservable_today`)
and the BE composes the right CTA from that. The row stops carrying
`reservable`; `season` stays as a string the BE parses.

This also kills `seasonVerdictHTML` on the FE — the season-vs-today
comparison moves to the BE alongside everything else.

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

**Prior art: how rec.gov and Aspira NextGen model this.**

Both vendors handle the same domain (sites the public reserves) with richer
hierarchies than what we're proposing. Useful as a sanity check on what
we're choosing not to model.

*rec.gov (Recreation.gov RIDB API)* — three-level tree:

- **RecArea** — the umbrella (a national forest, a wilderness, a corps lake).
  Has `RecAreaID`, geometry, governing agency (NPS / USFS / BLM / Army Corps),
  email, phone, parent organization. Carries marketing copy + media.
- **Facility** — what we'd call a campground. `FacilityID`, type
  (`Campground`, `Day-use`, `Lookout`, `Lodge`...), point coordinate,
  `FacilityReservationURL`. Belongs to one RecArea (sometimes more via
  RECAREAFACILITIES join).
- **Campsite** — the bookable unit. `CampsiteID`, type (`STANDARD ELECTRIC`,
  `WALK TO`, `RV`...), max occupancy, equipment allowed (`Tent`, `RV up to
  N ft`), accessibility flags, attributes (shade, picnic table, fire ring).
  Belongs to one Facility.
- Plus dimension tables: `Permits`, `Tours`, `Activities`, `Events`, `Media`.

*Aspira NextGen* — similar three-level shape via `/api/maps`:

- **Park** (top-level node, `mapType=2` at PC, region-nested at BC/WA) —
  `transactionLocationId`, `mapId`, name, image. The bookable destination.
- **MapLink / sub-area** — what Aspira calls a "loop" or "campground within
  the park" (Banff has 7 of these: Tunnel Mountain Village I, II, etc.).
  Has its own `childMapId` + `transactionLocationId` + `resourceLocationId`
  (the field we just learned matters for WA's deeplink).
- **Resource** — the individual site. Equipment-typed (RV / tent / cabin),
  with a lat/lng inside the loop and per-night status codes (the integers
  we map in `AspiraStatus.classify`).
- Plus: `EquipmentCategory`, `BookingCategory`, `Amenity`, `Feature` taxonomies.

**What rec.gov + Aspira have that we don't:**

1. **A site/resource level below the campground.** They model individual
   campsites (#A23, the walk-to tent pad) as first-class entities. We
   collapse to one campground point. Costs us: can't show "this campground
   has 12 RV-electric sites and 30 tent-only" without scraping per-site
   data.
2. **A park-level container distinct from individual campgrounds.** Banff
   National Park is one entity; the 7 sub-loops are children. Our
   `parent_poi_id` self-FK partly captures this, but we don't model loops
   at all.
3. **Facility/site type taxonomies.** rec.gov knows a "Lookout" is not a
   "Campground"; Aspira knows "RV with electric" is not "Walk-to tent."
   We have one `category='campground'` regardless.
4. **Equipment compatibility.** Aspira's deeplink takes
   `equipmentCategoryId` because the lookup is "what RVs fit in this site."
   We pass the "any equipment" sentinel.
5. **First-class amenity + activity tables.** We list amenities as a string
   array on the campground row; rec.gov + Aspira have join tables and
   filterable taxonomies (e.g. "show me parks with horseback riding").

**What we're explicitly choosing to skip (and why):**

- **Site-level (Campsite/Resource) modeling.** The product surface is "find
  a campground, click through to book." We don't run our own booking flow,
  and the heat strip aggregates *across* sites — we never need to know
  which specific site is open, only how many are. Adding a 4th table
  doubles the schema for ~zero user-visible benefit. Revisit if/when we
  build per-site availability tracking, which would also force us to deal
  with rec.gov's tier-1 rate limits.
- **Sub-area / loop modeling within a park.** A natural fit for the
  parent_poi_id self-FK chain (park → loop → site), but we'd need to
  source it. rec.gov + Aspira have it; our curated JSONs don't, and we'd
  scrape per-loop data per park to backfill. Skip until something needs
  per-loop UX.
- **Equipment / amenity taxonomies as join tables.** We carry amenities as
  a flat string list. Promoting to a dimension table is a DX nicety
  (`SELECT pois WHERE amenity = 'horseback'`) but the FE doesn't filter on
  amenity today. Defer.
- **Activities / events / tours / media.** All of rec.gov's secondary
  endpoints are no-ops for us; we surface the booking link, the operator
  does the rest.

**What this prior-art review *does* tell us to add:**

- **Facility type beyond `category='campground'`.** Even within campgrounds,
  there's a meaningful split: `tent-only`, `rv-only`, `mixed`,
  `backcountry`, `cabin`. Aspira's `BookingCategory` does this; rec.gov's
  `Type` field does this. Worth promoting from JSONB into a real column,
  even if we keep the taxonomy small. Punt to a follow-up RFC after we
  have data on what we're actually getting from each source.
- **Operator vs. agency.** rec.gov treats RecArea's `ParentOrganization`
  (NPS, USFS) separately from the contractor running on-site bookings.
  Our `governing_body` covers ParentOrganization; we don't model the
  contractor. Probably fine — it's a level of fidelity that's not
  user-visible.

---

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

## Field audit: every property in `data/*.geojson` mapped to a home

Walking the actual columns we ingest today against the proposed model.
Anything that doesn't have a row gap-checks the design.

> **Caveat about the geojsons.** The files in `data/*.geojson` are the
> *merged + transformed* artifacts the importer reads — not the raw
> upstream payloads. They've already been:
>
> - normalized to a flat property bag (`fetch_campgrounds.py` maps
>   uscampgrounds.info CSV columns → `category`, `typeLabel`, `amenities`)
> - merged across sources (`fetch_parks_canada.py` overlays curated AB/BC
>   JSON onto the US campgrounds set; `fetch_aspira_bc_wa.py` stamps
>   aspira IDs onto BC/WA features)
> - filtered (`fetch_tesla_superchargers.py` keeps only `status='OPEN'`,
>   drops `effectivePricebooks` + `availabilityProfile` from Tesla's
>   raw payload)
> - shaped for the FE's existing layer code (color, group, status fields
>   exist *because* `index.html`'s SC layer reads them)
>
> So the audit below is a fair check on "what does the BE-to-FE wire
> shape need," but not on "what data exists upstream that we could be
> capturing." For that, the raw inputs matter.

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

### Gaps the RFC needs to close

1. **`pois.country`** — promote from per-type to base. Drives FE behavior
   (US-vs-CA park-system fallback search) and BE adapter selection.
2. **`pois.phone`** — base, not per-type. Three of four POI types carry it.
3. **`pois.address`** — JSONB on base. Avoids "every type re-models street/city/state/postcode."
4. **`pois.info_url`** — base, replaces the parks_canada_url / parks_alberta_url /
   bcparks_url / website per-type sprawl. One column for "non-booking
   informational link," BE composes "Park info on …" / "Visit website" /
   "Find on Tesla.com" buttons from `(info_url, governing_body)`.
5. **`Campground.photoUrl`, `activities`, `cellCoverage`, `ratingReviews`**
   — present in geojson, missing from the RFC's `Campground` shape. Add.
6. **`Park.designation`** — `Des_Tp` ("National Park" vs "Wilderness" vs
   "National Recreation Area") is a meaningful display distinction.
7. **`Park.officialName` / `Loc_Nm`** — keep for the small set where it
   differs from `Unit_Nm`; otherwise null.

The **drop** column is also the point: `enriched`, `color`, `group`,
`type`, `typeLabel`, `parent_name`, `parent_type`, `reservable` —
poller-internal artifacts or fields the model now derives. They've been
leaking to the wire for no reason.

### Raw upstream — what the geojsons currently strip

Looking past the merged artifacts, the upstream sources carry richer data
the poller could capture but doesn't:

**uscampgrounds.info CSV (fetch_campgrounds.py source).** Has per-site
fields the merge collapses: `ELEV` (elevation), per-site `LON`/`LAT` for
GPS-precise spots, `AGENCY` (operator separately from manager).

**Tesla cua-api `/locations/<slug>` (fetch_tesla_superchargers.py source,
cached at `data/pricing-cache/<id>.json`).** Has:

- `accessHours` — supercharger access window (most are 24/7, some
  hotel/mall lots aren't)
- `accessType` — e.g. "Restricted", "Public"
- `address` — structured (street/city/state/postcode/country/centroid)
- `amenities` — restroom, food, lodging, shopping (we currently drop)
- `effectivePricebooks` — pricing tiers (the backend caches these for
  `/api/pricing/{slug}` — relevant for the `features.pricing` capability)
- `isTrailerFriendly`, `openToNonTeslas`, `openToPublic`, `ownershipType`
- `timeZone` — useful for displaying access hours
- `commonSiteName` — sometimes more user-friendly than `name`

**OSM Overpass for Planet Fitness (fetch_planet_fitness.py).** OSM tags
include `wheelchair`, `level`, `internet_access`, `payment:*`, social
links — currently we keep only the explicit address fields.

**rec.gov RIDB (used for enrichment).** Captured today: `cell_coverage`,
`rating_reviews`. Available but not captured: facility-level
`Description`, `Directions`, `Stay limit`, `Reservation cutoff`, `Open
season`, fees, photo URLs.

**PAD-US (national/state parks).** We keep five fields. The dataset has
~30+ more, including `Pub_Access` (open/restricted), `Access_Typ`
(walk-in/drive-up), `IUCN_Cat` (conservation classification), `Date_Est`,
`Own_Type` (federal/state/private/joint).

**Aspira `/api/maps` (already partly captured).** We capture
`transactionLocationId`, `mapId`, `resourceLocationId`, `title`. The same
endpoint also has `mapImageUrls` (per-park map graphic), `description`,
`directions`, `cancellationPolicy`, `arrivalInstructions`, plus
sub-area-level (`mapLinks`) names + coordinates that we ignore.

**Aspira `/api/availability/map` (drawer fetch).** Sub-area names get
collapsed to integer per-day status codes; we throw away `loop_name`,
`equipment_compatibility`, per-site detail.

#### What of this is worth capturing?

In terms of obvious wins on the FE:

- **Tesla amenities + accessHours** — already in the cache; the SC popup
  could surface "24/7 · 16 stalls · restroom · food" with no new fetch.
- **Tesla `address` structured** — drop the assembled `street` field,
  switch to the raw object so we get country and centroid for free.
- **rec.gov facility `Description`/`Directions`** — drawer expandable
  detail section is starved for content for non-recgov pins; rec.gov
  ones could carry it.
- **PAD-US `Date_Est`, `Own_Type`** — minor flavor for the park drawer.

Things the user won't ever see (skip):

- Aspira sub-area-level names. Useful only for per-loop UX, which we
  decided against (decision #11).
- Tesla `effectivePricebooks`. Already captured separately by the
  backend's pricing route.
- OSM `level`/`wheelchair`/etc. Out of scope unless a use case appears.

This isn't a forcing function for the data model. The model is JSONB on
the per-type Kotlin shape (`Campground`, `Supercharger`, etc.), which
already accommodates anything we want to capture later. The point is
that the audit should be of *what we want from upstream*, not what the
poller currently happens to copy through.

> **Action item, not RFC scope.** A separate small task: re-survey each
> poller's raw payload, decide what's worth capturing, and update the
> per-type Kotlin shapes + JSONB conventions. The RFC's structural
> decisions don't depend on which fields land — only on the fact that
> they have a shape-stable home.

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
