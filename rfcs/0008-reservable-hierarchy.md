---
title: Reservable hierarchy + POI-keyed availability surface
authors:
  - William Chen
created: 2026-06-12
last_updated: 2026-06-12
rfc_pr: TBD
status: Draft
---

# Proposal: Reservable hierarchy + POI-keyed availability surface

## Summary

Replace the campground/site terminology in the public API with a generic
**reservable** abstraction. A reservable is anything-you-can-hold-at-a-place:
campsite today; backcountry permit, day-use parking, timed-entry ticket
later. Reservables are addressed by composite ID (`type:vendor:vendor_id`)
and associated to one or more POIs through an N:M relationship that
denormalizes upstream-vendor hierarchies (Aspira's park → area → resource
tree, rec.gov's facility → campsite, Camis's facility → site) into a flat
"these reservables belong to this POI" lookup.

The current `/api/campsite/availability/{poi_id}` route is replaced by
`/api/poi/{poi_id}/availability` (per-day rollup over child reservables) and
a new family of `/api/reservable/{rid}/...` routes for per-reservable
detail. No version prefix; we sunset the old paths.

## Motivation

PR #198 introduced the `BookingProvider` port — one adapter per upstream.
PR #199 built a week-grid availability search that fetches per-day status
for one POI. Both work for campsites today.

Two observations forced a redesign:

1. **Aspira's `/api/availability/map` already returns per-individual-site
   data** (`resourceAvailabilities`) which we currently discard. Per-site
   granularity is achievable across all three providers (rec.gov, Aspira,
   Camis); the current model just hasn't pulled it through.

2. **Roadmap covers reservables that aren't campsites.** Backcountry permits
   (Half Dome), timed-entry tickets (Arches), day-use parking (Mt Whitney),
   lottery zones — all are alert-able, monitor-able, potentially
   auto-bookable. None fit a `/campground/{id}/site/{id}` URL. The right
   abstraction is one level up: reservable.

Continuing to build on the campsite-shaped model means painting ourselves
into a corner and migrating later. The composite ID + N:M shape is small
enough to introduce now, before more code locks in the campsite-shape
assumption.

## Goals

- One URL family that addresses any future reservable type without a new
  route convention.
- Composite IDs that humans can read in logs and that adapters can dispatch
  on without a DB lookup.
- N:M reservable→POI shape so the same campsite can show up under both its
  campground POI and (eventually) its parent-park POI without duplicating
  the reservable record.
- A clean per-vendor-per-type adapter contract (2D registry) so adding a
  Half Dome permit adapter is a one-directory addition.
- `/api/poi/{poi_id}/availability` replaces today's per-campground rollup
  call without breaking the FE drawer's week-grid contract.

## Non-Goals

- **Park POIs**: not ingesting Yosemite NP / Yellowstone NP as their own
  POIs in v1. The N:M schema supports them; we'll populate when there's a
  caller. Each reservable has exactly one POI parent today.
- **Filter taxonomy**: shipping `type` and `min_nights` only on the listing
  endpoint. Equipment, party-size, attributes (firepit, picnic table)
  deferred until real demand surfaces and providers' attribute schemas
  prove they normalize.
- **Permit / ticket adapters**: not building them now. The schema,
  composite-ID format, and 2D registry are forward-compatible; actual
  adapter code lands when we have a permit or ticket use case.
- **Alert-management UI**: still tracked separately. RFC 0007 deferred this;
  it stays deferred. Alerts move from `campground_id` to `reservable_id` as
  part of this RFC's migration.

## Proposal

### Terminology

| Term | Meaning |
|---|---|
| **POI** | A place on the map. Already exists. Categories include `campground`, `supercharger`, `national-park`, etc. |
| **Reservable** | Anything-you-can-hold-at-a-place. Composite ID `type:vendor:vendor_id`. New first-class entity. |
| **Reservable type** | `site` ships in this RFC. `permit` and `ticket` slot in via future RFCs. |
| **Vendor** | The upstream booking system. `recgov`, `aspira_pc`, `aspira_bc`, `aspira_wa`, `camis`. |

### Composite ID format

Wire format: `{type}:{vendor}:{vendor_id}` — colon-separated, plain string.

Examples:

```
site:recgov:330257                       # one Lower Pines campsite
site:aspira_bc:-2147483190               # one BC Parks resource
site:camis:AB-12-7                       # one Alberta Parks site (when adapter exists)
permit:recgov:445859                     # Half Dome permit slot (future)
ticket:nps:arches-2026-08-01-09:00       # Arches timed entry (future)
```

Vendor IDs are opaque to the BE. Aspira's negative ints are URL-safe (`-`
is a valid path character). Colons inside the vendor_id are allowed
(the parser splits on the first two colons only).

A sealed Kotlin type at the model layer:

```kotlin
data class ReservableId(
    val type: ReservableType,            // sealed: Site (more later)
    val vendor: BookingProviderId,       // existing enum from PR #198
    val vendorId: String,
) {
    fun encode(): String = "$type:$vendor:$vendorId".lowercase()
    companion object {
        fun parse(raw: String): ReservableId? = ...
    }
}
```

### URL surface

Three families, all under `/api/`. No version prefix. The old
`/api/campsite/availability/{poi_id}` route is removed in the same PR
that ships these.

```
# Per-POI rollup (replaces the old per-campground availability route)
GET /api/poi/{poi_id}/availability
   ?start=YYYY-MM-DD&days=N&min_nights=N&type=site&force=1
→ per-day rollup over the POI's reservables. Drives the week grid.

# Per-POI listing (filter + qualify)
GET /api/poi/{poi_id}/reservables
   ?date=YYYY-MM-DD&min_nights=N&type=site
→ list of reservables qualifying the arrival day, with names + status.

# Per-reservable detail
GET /api/reservable/{rid}
→ reservable info: name, vendor, type, parent POI(s), capabilities, raw
  upstream blob.

GET /api/reservable/{rid}/availability
   ?start=YYYY-MM-DD&days=N
→ one reservable's per-day status across the window.
```

Filter params on `/api/poi/{poi_id}/reservables`:

- `type` — defaults to `site`. Passes through to the registry to scope the
  adapter set considered.
- `min_nights` — same semantics as availability route. Applies to per-day
  qualification.
- `date` — required. The arrival day to qualify against.

`/api/poi/{poi_id}/availability` reuses the same param shape minus `date`.

Other filters (equipment, party size, attributes) are deferred. The route
parses unknown params as no-op so future additions are non-breaking.

### Data model

Three new tables. Existing `pois` and `alerts` tables get column changes.

```sql
-- A reservable. One row per (vendor, vendor_id) pair. The composite
-- ReservableId is rebuilt from these columns; we don't store the
-- encoded form because that's a presentation concern.
CREATE TABLE reservables (
  id              BIGSERIAL PRIMARY KEY,    -- internal pk for joins
  type            TEXT NOT NULL,            -- 'site' for now
  vendor          TEXT NOT NULL,            -- matches BookingProviderId
  vendor_id       TEXT NOT NULL,
  name            TEXT,                     -- 'A12', 'FS1-20', etc.
  loop            TEXT,                     -- 'Loop A', 'AREA WHITE RIVER', etc. (provider-defined)
  site_type       TEXT,                     -- 'STANDARD', 'TENT ONLY', etc. (recgov campsite_type)
  raw             JSONB,                    -- full upstream blob, source of truth
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (type, vendor, vendor_id)
);

-- N:M between reservables and POIs. A reservable can belong to multiple
-- POIs (campground + parent park). At v1 each reservable has exactly one
-- POI parent (campground); the schema is N:M so future park-POI ingestion
-- doesn't need a migration.
CREATE TABLE reservable_pois (
  reservable_id   BIGINT NOT NULL REFERENCES reservables(id) ON DELETE CASCADE,
  poi_id          BIGINT NOT NULL REFERENCES pois(id) ON DELETE CASCADE,
  PRIMARY KEY (reservable_id, poi_id)
);
CREATE INDEX ON reservable_pois (poi_id);

-- Existing alerts table: campground_id (TEXT) becomes reservable_id (TEXT,
-- composite). Existing rows get fanned out at migration (see Migration
-- section). Eventually drop campground_id column.
ALTER TABLE alerts ADD COLUMN reservable_id TEXT;
CREATE INDEX ON alerts (reservable_id);
-- (campground_id stays during migration window; dropped in a follow-up.)
```

Reservables get populated by ETL adapters at ingest time. The `raw` field
preserves the full upstream JSON blob — data trust principle: user/admin
queries can see exactly what we got from rec.gov / Aspira, not what we
chose to project.

### Three-section ETL registry

**The YAML registry gains two new top-level sections.** The current
`poi_data` produces POIs (existing flow). A new `reservable_data`
produces reservables. A new `poi_reservable_joiner` produces N:M links
between POIs and reservables.

```yaml
data_sources:
  - slug: recgov-campgrounds        # RIDB facility list
  - slug: recgov-campsites          # rec.gov per-facility /month
  - slug: aspira-maps-pc            # Aspira /api/maps
  - slug: aspira-resources-pc       # Aspira per-campground /api/availability/map

poi_data:                           # produces Poi.* rows in `pois`
  - name: Federal Campgrounds
    category: campground
    subcategory: federal
    etls:
      - slug: federal-campgrounds
        adapter: RecGovCampgroundsEtl
        inputs: [recgov-campgrounds]

reservable_data:                    # produces Reservable rows in `reservables`
  - name: Federal Campsites
    etls:
      - slug: federal-campsites
        adapter: RecGovCampsitesEtl
        inputs: [recgov-campsites]

poi_reservable_joiner:              # produces N:M links in `reservable_pois`
  - name: Recgov campground/campsite join
    adapter: RecgovPoiReservableJoiner
```

Why three sections instead of one:

- **`poi_data` and `reservable_data` are independent.** Each reads its own
  raw data and writes its own table. They don't share intermediate state.
  Running them in either order produces a consistent system — POIs and
  reservables coexist without dependency on each other.

- **The N:M relationship is its own concern.** Computing links from
  `(reservables, pois)` to `reservable_pois` is a distinct operation that
  reads two populated DB tables and writes a third. It runs after both
  sides exist; it can re-run independently to pick up newly-added rows on
  either side. Coupling it inside the reservable ETL would force the ETL
  to know POI keying conventions — leaky abstraction.

- **Future reservable types stay simple.** When permits and tickets land,
  they're new `reservable_data` rows + new joiner adapters. The ETL
  contract doesn't grow a "is this a permit or a site?" branch; the
  joiner contract does the type-specific matching where it belongs.

The previous design (PR 2a's `ReservableTerminalEtl` marker interface +
`linksMissingPoi` orchestrator counter) collapsed reservable production
and POI linking into one ETL. That conflated two concerns and forced the
reservable ETL to know POI keying. The three-section split removes both
problems.

#### Section contracts

| Section | Adapter contract | Output | Writes |
|---|---|---|---|
| `poi_data` | `SourceEtl<DTO, List<Poi>>` (existing) | List of Pois | `pois` table |
| `reservable_data` | `SourceEtl<DTO, List<ReservableRepo.Input>>` | List of reservables | `reservables` table |
| `poi_reservable_joiner` | `PoiReservableJoiner` | List of (reservable_id, poi_id) pairs | `reservable_pois` table |

Each section has its own validator constraints, its own orchestrator
dispatch path, and its own admin route. The three share the slug
namespace (validator enforces uniqueness across sections so `inputs:`
resolution stays unambiguous).

#### Joiner contract

```kotlin
interface PoiReservableJoiner {
    val joinerSlug: String

    /**
     * Discover (reservable, poi) pairs that should be linked. Reads the
     * current state of both tables; emits link records the orchestrator
     * upserts via ReservableRepo.linkToPoi (idempotent — re-running on
     * existing links is a no-op).
     */
    fun discoverLinks(ctx: JoinerCtx): List<JoinerOutput.Link>
}

data class JoinerOutput(
    val links: List<Link>,
) {
    data class Link(
        val reservableId: Long,
        val poiId: Long,
    )
}

class JoinerCtx(
    val reservables: ReservableRepo,
    val pois: PoiRepo,
    val ctx: DSLContext,        // raw query escape hatch for joiner-specific SQL
)
```

For recgov: `RecgovPoiReservableJoiner` matches
`reservables.raw->>'campsite_id'` (or the parent facility id stored
in raw) to `pois.source_id` where `pois.source = 'federal-campgrounds'`.

For Aspira: `AspiraPoiReservableJoiner` matches `reservables.raw`'s
upstream identifiers (transactionLocationId + mapId) to
`pois.source_id = 'aspira-{txn}-{map}'` where `pois.source` is one of
`aspira-{wa,bc,pc}-pins`.

Joiners run on whatever cadence makes sense — daily nightly cron after
ETLs settle, or on-demand via the admin route. They're idempotent so
running twice is harmless.

### BookingProvider port shape

PR #198's port becomes 2D — registry keyed by `(vendor, type)`:

```kotlin
class BookingProviderRegistry(
    private val adapters: Map<AdapterKey, BookingProvider>,
    private val sourceToVendor: Map<String, BookingProviderId>,
) {
    data class AdapterKey(
        val vendor: BookingProviderId,
        val type: ReservableType,
    )

    fun forReservable(rid: ReservableId): BookingProvider? =
        adapters[AdapterKey(rid.vendor, rid.type)]

    fun forPoi(row: CampsiteProviderRefRow, type: ReservableType): BookingProvider? {
        val vendor = sourceToVendor[row.source] ?: return null
        return adapters[AdapterKey(vendor, type)]
    }
}
```

Adapter file layout:

```
service/booking/adapters/
├── recgov/
│   ├── site/RecGovSiteProvider.kt       (today's RecGovBookingProvider, renamed)
│   └── permit/RecGovPermitProvider.kt   (future)
├── aspira/
│   ├── site/AspiraSiteProvider.kt
│   ├── pc/AspiraPcSiteProvider.kt        ← or one-host-per-instance like today
│   ├── bc/AspiraBcSiteProvider.kt
│   └── wa/AspiraWaSiteProvider.kt
└── camis/
    └── site/CamisSiteProvider.kt        (still a stub)
```

Each adapter implements the same `BookingProvider` interface. It receives
`AvailabilityRequest` containing a `ReservableId`, dispatches based on its
own vendor+type knowledge, and returns the existing
`AvailabilityResponseDto`. Type and vendor are part of the registry key,
not the request body — the adapter knows which it is.

### POI listing endpoint shape

The big new shape. Returns the qualifying reservables for an arrival day,
including human-readable names + the raw upstream blob (gzipped over the
wire keeps payload reasonable):

```jsonc
GET /api/poi/{poi_id}/reservables?date=2026-09-12&min_nights=2&type=site

{
  "poi_id": 64512,
  "date": "2026-09-12",
  "min_nights": 2,
  "filter": { "type": "site" },
  "reservables": [
    {
      "rid": "site:recgov:330257",
      "type": "site",
      "vendor": "recgov",
      "name": "FS1-20",
      "loop": "AREA WHITE RIVER",
      "site_type": "STANDARD NONELECTRIC",
      "deeplink": "https://www.recreation.gov/camping/campsites/330257",
      "raw": { ...full upstream blob... }
    },
    ...
  ],
  "total_qualifying": 58,                   // reservables matching filters that qualify for this date
  "total_at_poi": 250                       // catalog count of reservables at this POI of this type
}
```

`raw` is the full upstream JSON blob preserved verbatim. Gzip handles
payload size; a 100-site response is ~10–15 KB gzipped.

### Per-POI availability rollup (replaces old route)

```jsonc
GET /api/poi/{poi_id}/availability?start=2026-09-12&days=7&min_nights=2&type=site

{
  "poi_id": 64512,
  "type": "site",
  "checked_at": "2026-09-12T17:30:00Z",
  "window": { "start": "2026-09-12", "days": 7 },
  "season": { "reopens_on": "2027-04-15" },   // null when N/A; rec.gov-only today
  "availability": [
    { "date": "2026-09-12", "available_count": 58, "total": 106 },
    ...
  ],
  "total_at_poi": 250,                        // catalog count of reservables at this POI for the queried type
  "cache": { "hit": true, "age_seconds": 142, "ttl_seconds": 600 }
}
```

The response ships only descriptive data: per-day raw counts, the
catalog total, and the optional season-reopen hint. Derived fields
(`status`, `state`, `summary`) are not included — the FE computes them
from these primitives.

The per-day `total` is "reservables the upstream returned data for that
date." It's typically equal to `total_at_poi` but can be smaller if
sites are missing from the upstream's response window (e.g. season
boundary).

`total_at_poi` is what the drawer uses to show "Upper Pines: 250 sites"
without a side fetch.

#### Deriving status / state on the FE

Each per-day row classifies as:

```
total === 0                          → closed
available_count === 0                → booked
available_count === total            → available
otherwise                            → partial
```

Window-level state classifies as:

```
all days closed AND season.reopens_on present → closed_for_season
all days closed AND no season hint            → empty
any day has available_count > 0               → success
all days booked, none closed                  → zero_available
```

Errors don't appear in the success body. A failed fetch returns a
non-200 response with an error body
(`{ "error": "rate_limited", "retry_after_s": 60 }`) — same shape as
today's existing error responses.

### Per-reservable detail

```jsonc
GET /api/reservable/site:recgov:330257

{
  "rid": "site:recgov:330257",
  "type": "site",
  "vendor": "recgov",
  "name": "FS1-20",
  "loop": "AREA WHITE RIVER",
  "site_type": "STANDARD NONELECTRIC",
  "max_people": 8,
  "equipment_types": ["Tent", "Small RV", "Large Tent Over 9X12`"],
  "attributes": { "Picnic Table": "Yes", "Fire Pit": "Yes" },
  "parent_pois": [{ "id": 64512, "name": "Mt Adams Recreation Area", "category": "campground" }],
  "deeplink": "https://www.recreation.gov/camping/campsites/330257",
  "raw": { ...full upstream blob... }
}

GET /api/reservable/site:recgov:330257/availability?start=2026-09-12&days=14
{
  "rid": "site:recgov:330257",
  "window": { "start": "2026-09-12", "days": 14 },
  "availability": [
    { "date": "2026-09-12", "status": "available" },
    ...
  ]
}
```

The per-reservable endpoint serves the future "drill into this site" UI
and the alert-detail page (one alert = one reservable; show me when this
specific site has been open).

### Caching

**No changes to the existing per-vendor cache classes.**
[`CachedAvailability`](../backend/src/main/kotlin/ca/floo/campsite/recgov/booker/availability/CachedAvailability.kt)
(rec.gov, keyed by month) and
[`CachedAspiraAvailability`](../backend/src/main/kotlin/ca/floo/roadtrip/repo/CachedAspiraAvailability.kt)
(Aspira, keyed by window) stay as-is. The reservable redesign sits on
top of today's cache topology — the only mechanical cache-related change
is that `AspiraAvailability` gains a `byResource` field that was being
discarded today (the per-resource availability data we already get from
`/api/availability/map` but throw away after parsing).

What this means in practice:

- **Per-POI rollup endpoint** (`/api/poi/{poi_id}/availability`) hits the
  same per-vendor cache the old route hit. Same TTL, same hit/miss
  semantics, same `cache.age_seconds` block in the response.
- **Per-reservable detail endpoint** (`/api/reservable/{rid}/availability`)
  shares the cache with the per-POI rollup. Loading one site's status
  after loading the campground rollup is free.
- **`min_nights` does not fragment the cache.** It's a classifier
  parameter, not a cache key. The cache stores the upstream's per-month
  view; the classifier runs on every request to compute the per-day
  status under the requested stay-length window. Different `min_nights`
  values produce different responses from the same cached upstream.
- **Reservables table is a catalog, not a cache.** It stores name, loop,
  type, raw upstream metadata — data that changes by the day at most.
  Refreshed via ETL (existing pipeline), not request-time. Catalog reads
  hit Postgres directly; the table is small enough to live in
  `shared_buffers`.

### Migration

**Old routes deleted in the same PR that ships the new ones.** No
`/api/campsite/availability/{poi_id}` survives. The FE in PR #199 (week
grid) gets updated to call `/api/poi/{poi_id}/availability` in the same
PR — single atomic move.

**Reservables population** is just the existing data_fetch / data_import
pipeline. Once the new `reservable_data` and `poi_reservable_joiner`
sections exist (PR 2 below), running `make data-fetch
TARGET=recgov-campsites` followed by `make data-import TARGET="Federal
Campsites"` produces the catalog. The joiner runs separately. There's
no separate "backfill" step — backfill is just running the pipeline.

For Camis: still a stub. The reservable_data row exists with the
adapter wired but the underlying upstream client doesn't fetch yet.

**Alerts migration** (per RFC 0007 lifecycle):

The current `alerts` table has `campground_id` (TEXT, rec.gov's facility
id). With site-level alerts, each existing alert fans out to N alerts —
one per site at that campground.

Sequence at deploy:

1. New `reservable_id` column added (nullable initially).
2. For each existing active alert: look up the campground's reservables
   by the campground POI; create N new alert rows (one per reservable)
   with `reservable_id` set, status copied from parent.
3. Mark the original campground-level row `status='migrated'` so the
   poller stops processing it.
4. Poller starts evaluating site-level alerts. The poller's existing
   dedup logic (last-poll / last-alert state) prevents the user from
   getting N notifications for one upstream change — first match in a
   batch fires; siblings note the user has been notified.

Alert count balloon is real: Upper Pines has ~250 sites, so a
campground-level alert becomes 250 site-level alerts. That's fine for
the poller (it's still one upstream call per cycle to read the whole
campground; the dedup happens after); and it's the right semantics —
the user said "watch this campground" and that's what's watched.

### Rollout

| PR | Scope | Risk |
|---|---|---|
| 1 | Schema: `reservables` + `reservable_pois` tables + jOOQ + `ReservableRepo` | Low — additive (landed) |
| 2 | Registry-section split: PoiRegistry gains `reservable_data` + `poi_reservable_joiner` lists; validator + EtlOrchestrator + IngestController dispatch each section. No new ETLs yet. | Medium — touches registry validation + orchestrator dispatch |
| 3a | recgov-campsites fetcher + ETL emitting reservables (no POI knowledge); recgov joiner adapter | Medium — new upstream fetch at scale |
| 3b | aspira-{wa,bc,pc}-resources fetchers + ETL emitting reservables; aspira joiner adapter | Medium — three tenants, WAF-prone upstream |
| 4 | BookingProvider port becomes 2D registry; adapters move to `<vendor>/site/` subdirs; no behavior change | Low — refactor, tests gate |
| 5 | New routes (`/api/poi/{id}/...`, `/api/reservable/{rid}/...`); old routes deleted; FE migrates in same PR | Medium — atomic API rename, FE+BE in lockstep |
| 6 | Alerts migration: add `reservable_id` column; backfill at deploy; poller switches to per-reservable evaluation | Medium — touches the alert/poll code path |

PRs 1-4 are forward-compatible no-ops to the running system. PR 5 is
the atomic API switch. PR 6 closes the loop on the alert UI.

**Earlier marker-interface design abandoned.** A previous version of
this rollout proposed a `ReservableTerminalEtl` marker interface that
let one orchestrator path emit either Pois or reservables based on
runtime type. That collapsed two concerns (catalog production + POI
linking) into one ETL and forced reservable ETLs to know POI keying.
The three-section design above replaces it: each ETL has one job, the
joiner has one job, and the orchestrator has one dispatch per section.

## Rationale

**Why composite ID strings, not opaque hashes?**
Debuggability. `site:recgov:330257` in a log line is readable. A hash
(`rsvbl_a1b2c3d4`) requires a DB lookup to interpret. Both work; the
string costs nothing extra in payload size given gzip and saves operator
time forever.

**Why N:M from day one when v1 has one parent per reservable?**
Cheap to model now (one extra table), expensive to migrate later (every
caller re-keys, every cache re-fills). And we know we'll need it: the
"browse Yosemite NP" use case is on the table; the adjacency table just
sits unused until then. No runtime cost.

**Why three sections in the YAML registry?**
Each section maps to one concern: produce POIs, produce reservables,
link them. The previous attempt (a marker interface on the ETL +
runtime dispatch in the orchestrator) collapsed catalog production and
POI linking into one operation. That meant the reservable ETL had to
know how each vendor keys its POIs (`pois.source_id = "aspira-{txn}-{map}"`
for Aspira; `pois.source_id = "{FacilityID}"` for recgov) — leaky
abstraction. With sections, the linking lives in dedicated joiner
adapters that own that knowledge, and ETLs only know their own
upstream's identity scheme. Two new YAML sections cost ~150 LOC of
registry-side wiring and remove the wrong abstraction.

**Why `type` as a sealed enum, not an open string?**
The FE renders different UI for sites vs permits vs tickets. Letting
adapters declare arbitrary strings means the FE has to handle unknown
types, which is just a default-to-generic render. Bounded enum keeps the
type system useful: when we add `permit`, we add a sealed-class branch
and the compiler tells us every render path that needs updating.

**Why ship `site` only and not pre-build `permit` / `ticket`?**
We don't have data for the others. Designing their response shape from
imagination would shake out wrong; doing it once we plumb a real Half
Dome permit through gets the shape right. The schema and registry are
ready; the adapters slot in later.

**Why drop `/api/campsite/...` instead of aliasing?**
Two routes for the same thing is a maintenance tax forever. The FE has
exactly one caller of the old path (PR #199's week grid); migrating it
in the same PR that ships the new path is cheap. Clean cut.

**Why no `status` / `state` / `summary` in the response?**
Those are derived presentational concerns. The BE ships data; the FE
composes presentation. `status` is `(available_count, total)` math, two
lines of FE code. `state` is the same plus the season hint. `summary`
("5 nights available · weekends full") is a rendered string the BE
shouldn't own — locale, screen size, user prefs all live on the FE.
Leaving these fields in invites drift between the derived label and the
underlying counts; better to ship the source of truth and let the FE
classify on read.

The one field we do keep that's not strictly raw: `season.reopens_on`
(when the upstream surfaces it, today rec.gov-only). That's not
derivable from per-day counts alone — it's an upstream signal worth
preserving.

Errors stay on non-200 responses: if the upstream fetch fails, we
return `503` with `{ "error": "rate_limited", "retry_after_s": 60 }`.
We don't bury error states inside a 200 body. Today's response shape
has both `state: "error"` *and* non-200 paths; collapsing to non-200
only is cleaner.

**Why not surface Aspira's tree (park → area → resource) to the FE?**
Aspira's tree is an upstream artifact, not user-facing knowledge. A user
who clicks Goldstream Provincial Park doesn't think "I'm browsing the
Aspira map node tree." They think "I'm browsing reservables at this
park." The adapter handles the tree internally; the API surface is flat.

## Unresolved questions

- **Aspira sub-area names.** The `/api/availability/map` response gives
  resource IDs but no names. Names come from `aspira-leaves-{tenant}.json`
  (ETL output) or a per-resource `/resourcestatus` call. Plan: load
  leaves data at boot into the AspiraSiteProvider; resolve names from
  cache. Confirm the leaves data covers every resource we'd see in
  production, not just the ones we've already polled.
- **Camis adapter.** Still a stub. Filling in its reservable shape
  requires a real upstream investigation. Keep stubbed; flip
  `supportsAvailability` to `true` when implemented.
- **Reservable.raw size.** Recgov campsites carry 30-day availability +
  attributes; ~500-800 bytes per site uncompressed. 250 sites × this =
  ~150 KB per campground row. Acceptable for storage; gzipped over the
  wire is ~40 KB. If listing endpoints get sluggish, consider trimming
  raw to "the upstream record minus the 30-day blob" since availability
  is recomputed live anyway.
- **Backfill execution.** Initial population of the reservables table for
  existing campgrounds means hitting rec.gov + Aspira at scale. Need to
  rate-limit and probably stagger across days. The existing alert poller
  has the same upstream-respect machinery; the backfill ETL should
  borrow it rather than reimplement.
- **POI parent ambiguity.** A reservable from Aspira PC has
  `transactionLocationId` (the park) and `mapId` (a node in the park).
  Today we represent the campground POI by `(transactionLocationId,
  mapId)`. The "park" mapping is implicit in `transactionLocationId`.
  Future park-POI ingestion has to decide: do we also create park POIs
  for Aspira (one per `transactionLocationId`), or only for rec.gov
  (RECAREA-driven)? Capture in the future-park-POI RFC.
