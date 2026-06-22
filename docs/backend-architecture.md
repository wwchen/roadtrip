# Backend code structure

The backend is a single Ktor app split into strict layers. Dependency
direction matters more than package convenience: route code serializes HTTP,
service code owns use cases, repo code owns persistence, and model code owns
data shapes. Both halves of the app — the ETL pipeline that ingests data and
the HTTP API that serves it — share the same `models`, `repo`, and `clients`
substrate.

The simplest way to find anything: ask **what kind of code is this**, not
what feature it belongs to.

## Layers

```
routes  →  service  →  repo, clients
service →  models
repo    →  models, db
clients →  models
models  →  (stdlib + serialization only)
```

| Layer    | What lives here                                                                       | Depends on                                            |
| -------- | ------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `models` | Data classes, sealed types, validation results, request/response DTOs, upstream wire DTOs. **Pure types — no route/service/repo logic.** Prefer these DTOs over hand-built JSON. | stdlib + kotlinx.serialization + jOOQ generated types |
| `repo`   | Persistence + raw-capture I/O. SQL via jOOQ, filesystem reads/writes. **No HTTP, no business decisions.** | `models`, `db`                                        |
| `clients` | Outbound network calls (Mapbox, RecGov, Aspira, third-party APIs). **No DB, no HTTP routes.** | `models`                                              |
| `service`| Business logic. Interfaces, implementations, policy, ETL transforms, provider dispatch, cache fall-through, corridor math. **No Ktor types, no SQL strings.** | `models`, `repo`, `clients`                           |
| `routes` | Ktor handlers. Request parsing, shape validation, status codes, OpenAPI annotations, response serialization. **No SQL, no business orchestration.** | `service`, `models`                                   |

**Reading direction:** routes call service interfaces, services orchestrate
repos + clients + models. Anything pointing the other way is a smell.

**Interface-first rule:** if a feature has meaningful business behavior, the
service interface gets its own file and is the first place to read. The
implementation gets the matching `*Impl.kt` file. Example:

```
service/availability/
├── AvailabilityService.kt          # public use-case contract
├── AvailabilityServiceImpl.kt      # implementation
├── AvailabilityQueryService.kt     # POI/bulk query use cases used by routes
├── AvailabilityQueryServiceImpl.kt
├── AvailabilityTargetResolver.kt   # shared rid/poi → provider target resolver
├── AvailabilityDateResolver.kt     # target-local date/window policy
└── AvailabilityServiceError.kt
```

**File naming rule:** one meaningful top-level model per file, and the file
name must match the class name. API wire models use `Dto`; domain models do
not. Repo projections use `Row`. Mappers say what they map, for example
`AvailabilityResponseMapper.kt`.

**Cross-cutting:** `db/Db.kt` (Hikari + Flyway + jOOQ DSL bootstrap)
sits next to `repo` and is consumed by it. `routes/` registers itself
into `Main.kt`'s Ktor application.

## Directory layout

```
ca.floo.roadtrip
├── models                   # data shapes only
│   ├── api/                 # API request/response DTOs, one DTO per file when changed
│   ├── availability/        # provider-neutral availability domain models
│   ├── domain/              # core app value/domain types: Poi, Reservable, ReservableId
│   └── metadata/            # registry/ingest/upstream metadata models
│
├── repo                     # persistence + raw I/O
│   ├── Db.kt                # Hikari + Flyway + jOOQ DSL bootstrap
│   ├── PoiRepo.kt           # bbox query, UPSERT, sweep
│   ├── PoiServingRepo.kt    # map/detail serving projections
│   ├── ReservableRepo.kt    # reservable lookups and POI links
│   ├── RawCapture.kt        # data/raw/<slug>/ readers (newest single + multipart)
│   ├── IngestRunRepo.kt     # ingest_runs CRUD
│   ├── AvailabilitySnapshotRepo.kt
│   └── AvailabilityWatchRepo.kt
│
├── clients                  # outbound network only
│   ├── mapbox/MapboxDirections.kt
│   ├── mapbox/MapboxGeocoder.kt
│   ├── recgov/AvailabilityClient.kt
│   ├── cache/RecGovAvailabilityCache.kt
│   ├── cache/AspiraAvailabilityCache.kt
│   ├── cache/RouteCache.kt
│   └── aspira/AspiraAvailabilityClient.kt
│
├── service                  # business logic — etl + api both live here
│   ├── etl                  # ingestion pipeline
│   │   ├── framework/
│   │   │   ├── EtlOrchestrator.kt      # walks the chain, hands intermediates in-memory
│   │   │   ├── IngestController.kt     # per-target mutex + ingest_runs lifecycle
│   │   │   ├── BootRecovery.kt         # marks stale 'started' rows aborted at boot
│   │   │   ├── RegistryTargets.kt      # YAML → Target maps for fetch + import
│   │   │   ├── SourceEtl.kt            # the per-stage contract
│   │   │   └── TransformCtx.kt         # subcategory + arg lookups for transformers
│   │   └── vendors/
│   │       ├── recgov/RecGovCampgroundsEtl.kt
│   │       ├── aspira/AspiraLeavesEtl.kt
│   │       ├── aspira/AspiraJoinByNameEtl.kt
│   │       ├── bcparks/BcParksStrapiEtl.kt
│   │       ├── osmpf/PlanetFitnessEtl.kt
│   │       ├── reserveamerica/ReserveAmericaEtl.kt
│   │       └── tesla/TeslaIndexEtl.kt
│   ├── api                  # provider response mappers + fetch helpers
│   ├── availability         # availability interfaces, impls, policy, target resolution
│   ├── reservation          # ReservationProvider port + vendor adapters
│   └── scheduler            # generic scheduler + availability polling jobs
│
└── routes                   # HTTP shell — Ktor only
    ├── PoiRoutes.kt
    ├── PoisOnRouteRoutes.kt
    ├── ReservableRoutes.kt
    ├── RouteRoutes.kt
    ├── GeocodeRoutes.kt
    ├── HealthRoutes.kt
    ├── AvailabilityRoutes.kt
    ├── AvailabilityWatchRoutes.kt
    ├── AvailabilityDashboardRoutes.kt
    └── AdminIngestRoutes.kt        # /api/admin/data/* — triggers service.etl.framework
```

## Why this shape

**Both halves of the app share data.** The ETL pipeline writes pois rows;
the HTTP API reads them. They share `models.domain.Poi`, share
`repo.PoiRepo`, share the YAML `models.metadata.registry.PoiRegistry`. A
feature-cut layout
(`etl/` and `api/` as parallel modules with their own everything) would
duplicate or split those shared types — a layer cut keeps them in one
place.

**Sources of churn isolate.** Adding a new ETL adapter is one new file
under `service/etl/vendors/<vendor>/`, plus DTOs under `models/<vendor>/`,
plus one line in `EtlOrchestrator.registry`. Nothing in `repo`, `clients`, or
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
| `etl/EtlOrchestrator.kt` | `service/etl/framework/EtlOrchestrator.kt` |
| `etl/Upsert.kt` | `repo/PoiRepo.kt` |
| `etl/RawCapture.kt` | `repo/RawCapture.kt` |
| `etl/Envelope.kt` | `models/metadata/Envelope.kt` |
| `etl/Poi.kt` | `models/domain/Poi.kt` |
| `etl/SourceEtl.kt` | `service/etl/framework/SourceEtl.kt` |
| `etl/TransformCtx.kt` | `service/etl/framework/TransformCtx.kt` |
| `etl/ValidationResult.kt` | `models/metadata/ValidationResult.kt` |
| `etl/registry/*.kt` | `models/metadata/registry/*.kt` |
| `etl/<vendor>/*Etl.kt` | `service/etl/vendors/<vendor>/*Etl.kt`; per-source DTOs split into `models/<vendor>/` |
| `ingest/IngestController.kt` | `service/etl/framework/IngestController.kt` |
| `ingest/AdminIngestRoutes.kt` | `routes/AdminIngestRoutes.kt` |
| `ingest/BootRecovery.kt` | `service/etl/framework/BootRecovery.kt` |
| `ingest/Phase.kt` | `models/metadata/ingest/Phase.kt` |
| `ingest/RegistryTargets.kt` | `service/etl/framework/RegistryTargets.kt` |
| `api/PoiRoutes.kt` | split: `routes/PoiRoutes.kt` + `repo/PoiServingRepo.kt` + `repo/PoiRepo.kt` + `models/api/PoiSchemas.kt` |
| `api/RouteRoutes.kt` | `routes/RouteRoutes.kt` |
| `api/GeocodeRoutes.kt` | `routes/GeocodeRoutes.kt` |
| `api/HealthRoutes.kt` | `routes/HealthRoutes.kt` |
| `api/AdminSchemas.kt` | `models/api/AdminSchemas.kt` |
| `route/MapboxDirections.kt` | `clients/mapbox/MapboxDirections.kt` |
| `route/RouteCache.kt` | `clients/cache/RouteCache.kt` |
| `geocode/MapboxGeocoder.kt` | `clients/mapbox/MapboxGeocoder.kt` |
| `aspira/AspiraAvailabilityClient.kt` | `clients/aspira/AspiraAvailabilityClient.kt` |
| `aspira/AspiraAvailabilityRoutes.kt` | folded into `routes/AvailabilityRoutes.kt` + `service/availability/AvailabilityQueryService.kt` |
| `aspira/CachedAspiraAvailability.kt` | split: `clients/cache/AspiraAvailabilityCache.kt` + `service/api/AspiraAvailabilityService.kt` |
| `aspira/AspiraStatus.kt` | `models/metadata/aspira/AspiraStatus.kt` |
| `db/Db.kt` | `repo/Db.kt` |

## How a request flows

```
Browser
  ↓ HTTP
routes.PoiRoutes
  ↓ request DTO + shape validation
service.<feature>.<FeatureService>
  ↓ use-case orchestration
repo.PoiServingRepo
  ↓ jOOQ where persistence is needed
Postgres
  ↑ row/domain data
service.<feature>.<FeatureService>
  ↑ DTO/domain result
routes.PoiRoutes
  ↑ Ktor JSON response
```

Every arrow stays inside the dependency rules above. `routes` doesn't
touch SQL. `repo` doesn't compute corridors. `service` doesn't construct
HTTP responses.

## How availability flows

Availability has a stricter split because route, drawer, bulk, and watch
polling must all resolve the same reservables and providers.

```
routes.AvailabilityRoutes
  ↓ parse HTTP request, validate shape, map errors to HTTP
service.availability.AvailabilityQueryService
  ↓ POI/bulk use-case wiring
service.availability.AvailabilityService
  ↓ rid/rids use-case contract
service.availability.AvailabilityTargetResolver
  ↓ reservable → linked POI → provider_ref → ReservationProvider + date context
service.reservation.ReservationProvider
  ↓ upstream adapter call
models.availability.AvailabilityObservationBatch
  ↓ mapper
models.api.AvailabilityResponseDto
```

The watch poller uses the same `AvailabilityTargetResolver`, so live
availability and background polling cannot drift in how they pick a parent
campground provider or target-local date context. Route code never parses
`provider_ref` and never calls vendor adapters directly.

## How an ETL run flows

```
admin POST /api/admin/data/import/<poi_data-name>
  ↓
routes.AdminIngestRoutes
  ↓
service.etl.framework.IngestController
  ↓ acquire per-target mutex; create ingest_runs parent row
service.etl.framework.EtlOrchestrator.runPoiData(name)
  ↓ for each etl in the row's chain (in declared order)
service.etl.vendors.<vendor>.<Vendor>Etl
  ↓ parse(InputBundle) → validate → transform
  ├ intermediate stages: typed payload returned, kept in memory map
  └ terminal stage: List<Poi.*>
repo.PoiRepo.upsert(setOf(etl-slug), pois)   ← mark-and-sweep into pois
  ↓
service.etl.framework.IngestController
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
| New ETL adapter (e.g. another RIDB agency) | one row in `config/poi-registry.yaml`, one file under `service/etl/vendors/<vendor>/`, optionally DTOs under `models/<vendor>/`, one line in `EtlOrchestrator.registry` |
| New API endpoint | one file under `routes/`, one service interface + impl under `service/<feature>/`, repo methods under `repo/` if persistence is involved, request/response DTOs under `models/api/` |
| New outbound dependency (third-party HTTP) | one file under `clients/`, consumed by exactly one service |
| New table | Flyway migration under `backend/src/main/resources/db/migration/`, jOOQ regenerates, one repo file under `repo/` |
| New shared value type | one file under `models/`, no other layer changes |

## Anti-patterns to watch for

- **`routes` touching jOOQ or SQL strings.** Means business logic
  leaked into the HTTP shell. Push it into a service.
- **SQL outside `repo`.** Raw SQL strings, jOOQ DSL query construction,
  generated table references, and record mapping belong in repo classes.
  Routes/services should call named repo methods.
- **Hand-built route JSON.** If a route returns structured JSON, model the
  shape as a typed DTO (`@Serializable` data class)
  and serialize that instead of concatenating strings.
- **Mixed DTO/model files.** Do not add new grab-bag files such as
  `*Schemas.kt` that contain several unrelated top-level models. Put each
  changed DTO/domain model in a matching file.
- **Service implementation as the contract.** Consumers should not need to
  read `*Impl.kt` to know the feature surface. Add or update the interface
  first, then wire the implementation behind it.
- **`repo` knowing what an HTTP request looks like.** Means request
  parsing leaked downward. Repos take primitives + value types.
- **`service` building Ktor responses.** Same problem from the other
  side. Services return values; routes serialize.
- **`models` importing anything from `repo`, `service`, `clients`, or
  `routes`.** The leaf is sacred.
- **Two services depending on each other.** Either one of them is
  actually a repo (extract the shared persistence) or one belongs as a
  helper inside the other.
- **`clients` importing from `repo`.** Outbound HTTP shouldn't know
  about persistence; that's a service-layer concern.

## What's not in scope

- **Module separation.** Today everything ships as one Gradle module.
  The package-level rules above are enforced by code review and ktlint
  layout, not by gradle subprojects. If we want compile-time enforcement
  later, we can promote the layers to Gradle modules.
- **Hexagonal / ports-and-adapters.** This is a 5-layer cut, not
  hexagonal. We don't have a separate "ports" layer; interfaces sit
  inside `service` next to their callers. Promote to ports if a layer
  ever gains multiple implementations worth swapping at runtime. The
  one place we do this today is `service/reservation/`, where multiple
  reservation upstreams (rec.gov, Aspira, Camis) each need an adapter
  with the same contract — see [reservation-providers.md](reservation-providers.md).

## See also

- [reservation-providers.md](reservation-providers.md) — `ReservationProvider` port
  and the supported monitoring actions (availability, alerts,
  auto-book) per upstream reservation system.
- [adding-a-data-source.md](adding-a-data-source.md) — ETL pipeline
  walkthrough for adding a new POI data source.
