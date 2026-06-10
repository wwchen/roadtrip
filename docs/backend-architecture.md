# Backend code structure

The backend is a single Ktor app split into five top-level layers. Strict
dependency direction, no cycles, no peers. Both halves of the app — the
ETL pipeline that ingests data and the HTTP API that serves it — share
the same `models`, `repo`, and `client` substrate.

The simplest way to find anything: ask **what kind of code is this**, not
what feature it belongs to.

## Layers

```
routes  →  service  →  repo, client
service →  models
repo    →  models, db
client  →  models
models  →  (stdlib + serialization only)
```

| Layer    | What lives here                                                                       | Depends on                                            |
| -------- | ------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `models` | Data classes, sealed types, validation results, request/response DTOs, upstream wire DTOs. **Pure types — no logic.** Prefer these DTOs over hand-built JSON. | stdlib + kotlinx.serialization + jOOQ generated types |
| `repo`   | Persistence + raw-capture I/O. SQL via jOOQ, filesystem reads/writes. **No HTTP, no business logic.** | `models`, `db`                                        |
| `client` | Outbound network calls (Mapbox, Aspira availability, third-party APIs). **No DB, no HTTP routes.** | `models`                                              |
| `service`| Business logic. ETL transforms, query composition, cache fall-through, corridor math. **No Ktor types, no SQL strings.** | `models`, `repo`, `client`                            |
| `routes` | Ktor handlers. Marshalling, status codes, OpenAPI annotations. **No SQL, no business logic.** | `service`, `models`                                   |

**Reading direction:** routes call services, services orchestrate
repo + client + models. Anything pointing the other way is a smell.

**Cross-cutting:** `db/Db.kt` (Hikari + Flyway + jOOQ DSL bootstrap)
sits next to `repo` and is consumed by it. `routes/` registers itself
into `Main.kt`'s Ktor application.

## Directory layout

```
ca.floo.roadtrip
├── models                   # data shapes only
│   ├── Poi.kt               # sealed Campground/Supercharger/Park/PlanetFitness
│   ├── ProviderRef.kt
│   ├── Address.kt, RatingSummary.kt, CellSignal.kt
│   ├── Envelope.kt          # raw-capture wrapper shape
│   ├── ValidationResult.kt
│   ├── poi/                 # API-facing schemas (PoiRow, PoiFeature, request/response)
│   ├── route/               # RouteResponse, RouteCacheRow
│   ├── aspira/              # AspiraStatus, AspiraAvailability, AspiraLeaf, AspiraLeavesPayload
│   ├── recgov/              # RidbPageDto, Facility, FacilityAddress
│   ├── bcparks/             # BcParksPageDto, BcParksRow
│   ├── tesla/               # TeslaIndexRow, TeslaLocationDetail, TeslaAddress
│   ├── osmpf/               # PlanetFitnessRawDto, OverpassElement
│   ├── reserveamerica/      # ParsedPark, ReserveAmericaDto
│   ├── ingest/              # Phase, Target, RunOutcome, RunKind
│   └── registry/            # PoiRegistry, DataSourceEntry, EtlEntry, PoiDataEntry, Fetcher
│
├── repo                     # persistence + raw I/O
│   ├── Db.kt                # Hikari + Flyway + jOOQ DSL bootstrap
│   ├── PoiRepo.kt           # bbox query, UPSERT, sweep
│   ├── RawCapture.kt        # data/raw/<slug>/ readers (newest single + multipart)
│   ├── IngestRunsRepo.kt    # ingest_runs CRUD
│   ├── RouteCacheRepo.kt    # route_cache table I/O
│   └── AspiraAvailabilityCacheRepo.kt
│
├── client                   # outbound network only
│   ├── MapboxDirections.kt
│   ├── MapboxGeocoder.kt
│   └── AspiraAvailabilityClient.kt
│
├── service                  # business logic — etl + api both live here
│   ├── etl                  # ingestion pipeline
│   │   ├── EtlOrchestrator.kt      # walks the chain, hands intermediates in-memory
│   │   ├── IngestController.kt     # per-target mutex + ingest_runs lifecycle
│   │   ├── BootRecovery.kt         # marks stale 'started' rows aborted at boot
│   │   ├── RegistryTargets.kt      # YAML → Target maps for fetch + import
│   │   ├── SourceEtl.kt            # the per-stage contract
│   │   ├── InputBundle.kt          # what an ETL receives at parse()
│   │   ├── TransformCtx.kt         # subcategory + arg lookups for transformers
│   │   ├── recgov/RecGovCampgroundsEtl.kt
│   │   ├── aspira/AspiraLeavesEtl.kt
│   │   ├── aspira/AspiraJoinByNameEtl.kt
│   │   ├── bcparks/BcParksStrapiEtl.kt
│   │   ├── osmpf/PlanetFitnessEtl.kt
│   │   ├── reserveamerica/ReserveAmericaEtl.kt
│   │   └── tesla/TeslaIndexEtl.kt
│   └── api                  # request-handling logic (NOT routes — those are below)
│       ├── PoiQueryService.kt           # bbox query orchestration, default cats, corridor
│       ├── RouteService.kt              # cache lookup → fall-through to MapboxDirections
│       ├── GeocodeService.kt            # MapboxGeocoder wrapper + cache strategy
│       ├── AspiraAvailabilityService.kt # CachedAspira + AspiraAvailabilityClient glue
│       └── HealthService.kt
│
└── routes                   # HTTP shell — Ktor only
    ├── PoiRoutes.kt
    ├── RouteRoutes.kt
    ├── GeocodeRoutes.kt
    ├── HealthRoutes.kt
    ├── AspiraAvailabilityRoutes.kt
    └── AdminIngestRoutes.kt        # /api/admin/data/* — triggers service.etl
```

## Why this shape

**Both halves of the app share data.** The ETL pipeline writes pois rows;
the HTTP API reads them. They share `models.Poi`, share `repo.PoiRepo`,
share the YAML `models.registry.PoiRegistry`. A feature-cut layout
(`etl/` and `api/` as parallel modules with their own everything) would
duplicate or split those shared types — a layer cut keeps them in one
place.

**Sources of churn isolate.** Adding a new ETL adapter is one new file
under `service/etl/<vendor>/`, plus DTOs under `models/<vendor>/`, plus
one line in `EtlOrchestrator.registry`. Nothing in `repo`, `client`, or
`routes` changes.

**Tests follow the layout.** A unit test for an ETL transformer doesn't
need a database — it depends on `models` only. A repo test uses
Testcontainers Postgres. A routes test uses Ktor's test harness with
fakes for the service layer. Each layer is testable in isolation
*because* it can't reach across the boundary.

## Mapping the old layout to the new

The previous layout was feature-cut (`etl/`, `api/`, `ingest/`, `route/`,
`aspira/`, `geocode/`). Every package mixed concerns. Below is the
file-by-file relocation.

| Was | Is |
| --- | --- |
| `etl/EtlOrchestrator.kt` | `service/etl/EtlOrchestrator.kt` |
| `etl/Upsert.kt` | `repo/PoiRepo.kt` (merged with `api/PoiRoutes.kt`'s `fetchPois`) |
| `etl/RawCapture.kt` | `repo/RawCapture.kt` |
| `etl/Envelope.kt` | `models/Envelope.kt` |
| `etl/Poi.kt` | `models/Poi.kt` |
| `etl/SourceEtl.kt` | `service/etl/SourceEtl.kt` (interface) + `service/etl/InputBundle.kt` |
| `etl/TransformCtx.kt` | `service/etl/TransformCtx.kt` |
| `etl/ValidationResult.kt` | `models/ValidationResult.kt` |
| `etl/registry/*.kt` | `models/registry/*.kt` |
| `etl/<vendor>/*Etl.kt` | `service/etl/<vendor>/*Etl.kt`; per-source DTOs split into `models/<vendor>/` |
| `ingest/IngestController.kt` | `service/etl/IngestController.kt` |
| `ingest/AdminIngestRoutes.kt` | `routes/AdminIngestRoutes.kt` |
| `ingest/BootRecovery.kt` | `service/etl/BootRecovery.kt` |
| `ingest/Phase.kt` | `models/ingest/Phase.kt` |
| `ingest/RegistryTargets.kt` | `service/etl/RegistryTargets.kt` |
| `api/PoiRoutes.kt` | split: `routes/PoiRoutes.kt` + `service/api/PoiQueryService.kt` + `repo/PoiRepo.kt` + `models/poi/*.kt` |
| `api/RouteRoutes.kt` | `routes/RouteRoutes.kt` |
| `api/GeocodeRoutes.kt` | `routes/GeocodeRoutes.kt` |
| `api/HealthRoutes.kt` | `routes/HealthRoutes.kt` |
| `api/AdminSchemas.kt` | `models/ingest/AdminSchemas.kt` |
| `route/MapboxDirections.kt` | `client/MapboxDirections.kt` |
| `route/RouteCache.kt` | split: `repo/RouteCacheRepo.kt` + `service/api/RouteService.kt` |
| `geocode/MapboxGeocoder.kt` | `client/MapboxGeocoder.kt` |
| `aspira/AspiraAvailabilityClient.kt` | `client/AspiraAvailabilityClient.kt` |
| `aspira/AspiraAvailabilityRoutes.kt` | `routes/AspiraAvailabilityRoutes.kt` |
| `aspira/CachedAspiraAvailability.kt` | split: `repo/AspiraAvailabilityCacheRepo.kt` + `service/api/AspiraAvailabilityService.kt` |
| `aspira/AspiraStatus.kt` | `models/aspira/AspiraStatus.kt` |
| `db/Db.kt` | `repo/Db.kt` |

## How a request flows

```
Browser
  ↓ HTTP
routes.PoiRoutes
  ↓ POST body → request DTO (models.poi)
service.api.PoiQueryService
  ↓ bbox + cats → query plan
repo.PoiRepo.fetchByBbox(...)
  ↓ jOOQ
Postgres
  ↑ List<PoiRow>
service.api.PoiQueryService
  ↑ truncation, cat defaults, optional corridor filter → FeatureCollection
routes.PoiRoutes
  ↑ Ktor JSON response → wire
```

Every arrow stays inside the dependency rules above. `routes` doesn't
touch SQL. `repo` doesn't compute corridors. `service` doesn't construct
HTTP responses.

## How an ETL run flows

```
admin POST /api/admin/data/import/<poi_data-name>
  ↓
routes.AdminIngestRoutes
  ↓
service.etl.IngestController
  ↓ acquire per-target mutex; create ingest_runs parent row
service.etl.EtlOrchestrator.runPoiData(name)
  ↓ for each etl in the row's chain (in declared order)
service.etl.<vendor>.<Vendor>Etl
  ↓ parse(InputBundle) → validate → transform
  ├ intermediate stages: typed payload returned, kept in memory map
  └ terminal stage: List<Poi.*>
repo.PoiRepo.upsert(setOf(etl-slug), pois)   ← mark-and-sweep into pois
  ↓
service.etl.IngestController
  ↑ finalize ingest_runs row (success/fail counts)
routes.AdminIngestRoutes
  ↑ JSON outcome → wire
```

The orchestrator hands intermediates to downstream stages **in memory**;
nothing materializes to disk. Re-running the import is the recovery path
because every ETL is `f(inputs) → output`.

## Adding things

| Adding a... | Touches |
| ---------- | ------- |
| New ETL adapter (e.g. another RIDB agency) | one row in `config/poi-registry.yaml`, one file under `service/etl/<vendor>/`, optionally DTOs under `models/<vendor>/`, one line in `EtlOrchestrator.registry` |
| New API endpoint | one file under `routes/`, one service under `service/api/`, repo methods under `repo/` if persistence is involved, request/response DTOs under `models/<feature>/` |
| New outbound dependency (third-party HTTP) | one file under `client/`, consumed by exactly one service |
| New table | Flyway migration under `backend/src/main/resources/db/migration/`, jOOQ regenerates, one repo file under `repo/` |
| New shared value type | one file under `models/`, no other layer changes |

## Anti-patterns to watch for

- **`routes` touching jOOQ or SQL strings.** Means business logic
  leaked into the HTTP shell. Push it into a service.
- **SQL outside `repo`.** Raw SQL strings, jOOQ DSL query construction,
  generated table references, and record mapping belong in repo classes.
  Routes/services should call named repo methods.
- **Hand-built route JSON.** If a route returns structured JSON, model the
  shape as a typed DTO (`@Serializable` data class or existing schema class)
  and serialize that instead of concatenating strings.
- **`repo` knowing what an HTTP request looks like.** Means request
  parsing leaked downward. Repos take primitives + value types.
- **`service` building Ktor responses.** Same problem from the other
  side. Services return values; routes serialize.
- **`models` importing anything from `repo`, `service`, `client`, or
  `routes`.** The leaf is sacred.
- **Two services depending on each other.** Either one of them is
  actually a repo (extract the shared persistence) or one belongs as a
  helper inside the other.
- **`client` importing from `repo`.** Outbound HTTP shouldn't know
  about persistence; that's a service-layer concern.

## What's not in scope

- **Module separation.** Today everything ships as one Gradle module.
  The package-level rules above are enforced by code review and ktlint
  layout, not by gradle subprojects. If we want compile-time enforcement
  later, we can promote the layers to Gradle modules.
- **Hexagonal / ports-and-adapters.** This is a 5-layer cut, not
  hexagonal. We don't have a separate "ports" layer; interfaces sit
  inside `service` next to their callers. Promote to ports if a layer
  ever gains multiple implementations worth swapping at runtime.
