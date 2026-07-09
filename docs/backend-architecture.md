# Backend code structure

The backend is a single Ktor app split into strict layers. Dependency
direction matters more than package convenience: route code serializes HTTP,
service code owns use cases, repo code owns persistence, client code owns
outbound network calls, and model code owns data shapes.

The simplest way to place code is to ask **what kind of code is this**, not
which product feature first exposed it.

## Layers

```
routes  -> service -> repo, clients
service -> models
repo    -> models, db
clients -> models
models  -> stdlib + serialization only
```

| Layer | What Lives Here | Depends On |
| --- | --- | --- |
| `models` | Pure data shapes: request/response DTOs, domain values, validation results, upstream wire DTOs, repo projections when they are shared outside one repo. Prefer DTOs over hand-built JSON. | stdlib, serialization, generated DB types only when needed |
| `repo` | Persistence and raw-capture I/O. SQL, jOOQ DSL, table references, filesystem reads/writes. No HTTP and no business policy. | `models`, generated DB types |
| `clients` | Outbound network calls to third-party APIs. No DB and no HTTP routes. | `models` |
| `service` | Business logic: use-case orchestration, provider dispatch, cache fall-through, ETL transforms, policy, schedulers. No Ktor types and no SQL strings. | `models`, `repo`, `clients` |
| `routes` | Ktor handlers: request parsing, shape validation, status codes, OpenAPI annotations, response serialization. No SQL and no business orchestration. | `service`, `models` |

If code needs to cross a layer boundary in the wrong direction, reshape the
abstraction instead of importing around it.

## Package Patterns

Use stable package names for layers and generic extension points:

```
ca.floo.roadtrip
├── config/
├── http/
├── models/
│   ├── api/
│   ├── availability/
│   ├── domain/
│   └── metadata/
├── repo/
├── clients/
│   └── <vendor-or-api>/
├── service/
│   ├── api/
│   ├── availability/
│   │   ├── alert/
│   │   └── provider/
│   │       └── adapters/<vendor>/
│   ├── etl/
│   │   ├── framework/
│   │   └── vendors/<vendor>/
│   ├── notification/
│   ├── ratelimit/
│   ├── routing/
│   └── scheduler/
└── routes/
```

Prefer these generic forms in docs and reviews:

- `clients/<vendor-or-api>/*Client.kt` for outbound HTTP clients.
- `service/availability/provider/adapters/<vendor>/*` for availability-provider adapters.
- `service/etl/vendors/<vendor>/*Etl.kt` for ETL transforms.
- `models/<area>/*Dto.kt` for API and upstream wire shapes.
- `repo/*Repo.kt` for persistence boundaries.

Avoid encoding a current concrete vendor, file, or class inventory in this
architecture document. Concrete placement should be obvious from the layer
rules above and discoverable in the source tree.

## Naming Rules

Use one meaningful top-level model per file, and make the file name match the
primary type. API wire models use `Dto`; domain models do not. Repo query
results use `Row`. Mappers should say what they map.

Service interfaces with meaningful business behavior get their own file. The
implementation gets a matching `*Impl.kt` file. Helper classes belong beside
the service only when they are part of that use case, not because the caller
happens to live there.

## Application Wiring

The Ktor entrypoint should stay thin:

1. Load configuration and boot resources.
2. Install Ktor plugins.
3. Start runtime services and schedulers.
4. Register routes and static mounts.
5. Subscribe shutdown cleanup.

Construction-heavy wiring belongs in application composition helpers, not in
route files and not in business services.

## Request Flow

```
Browser
  -> routes
  -> service
  -> repo / clients
  -> service
  -> routes
  -> Browser
```

Routes parse HTTP and shape the response. Services decide what the use case
means. Repos persist and query. Clients call upstream APIs. Models cross
boundaries as typed values.

## Availability Flow

Availability is stricter because routes, drawer views, bulk lookups, and watch
polling must resolve the same provider targets.

```
routes
  -> service.availability
  -> target resolver
  -> service.availability.provider
  -> provider adapter
  -> provider-neutral availability observations
  -> API DTO mapper
```

Route code never parses provider references and never calls vendor adapters
directly. Provider-specific richness stays inside the adapter or in explicit
extension points owned by the availability-provider layer.

## ETL Flow

```
admin route
  -> ingest controller
  -> ETL orchestrator
  -> service.etl.vendors/<vendor>
  -> repo upsert
  -> [after campsite-data + joiners] catalog match
       -> CatalogMatcherService.run()      // shared-vendor-ref + geo+name passes
       -> CanonicalViewRepo.refreshCanonicalViews()
       -> CanonicalViewRepo.repointRepresentatives()
```

The ETL framework owns orchestration and run lifecycle. Vendor ETLs parse,
validate, and transform their upstream inputs. Persistence stays in repos.

Each vendor ETL writes its own per-vendor campground/campsite rows keyed on
`etl_source`; nothing merges across vendors at write time. Cross-vendor
identity lives in the `campground_matches` and `campsite_matches` tables,
written by the matcher after all vendor imports and joiners complete. The
matcher runs a deterministic shared-vendor-ref pass first, then a geo+name
heuristic pass gated by `MATCH_MAX_DISTANCE_M` / `MATCH_MIN_NAME_SIMILARITY`;
campsite matches are exact normalized `(loop, name)` equality inside a
campground match group. Every pass upserts into the pairwise match tables
and calls `recomputeMatchGroups()` to update `match_group_id` on the rows
themselves via iterative label propagation.

The read path serves campgrounds and campsites through the
`campground_canonical` and `campsite_canonical` materialized views. Each
view groups rows by `COALESCE(match_group_id, id)`, picks the richest row
per group as the winner (row-level, not field-level coalesce), and exposes
`member_ids BIGINT[]` and `member_sources TEXT[]` alongside every winning
row. `poi_campgrounds`, `availability_watch_target.campsite_id`, and
`availability.campsite_id` are re-pointed transactionally to the winning
row whenever the matcher shifts a group's winner, so all downstream keys
stay consistent with the canonical view. `REFRESH ... CONCURRENTLY` relies
on the unique index on `id` that V39 established.

The `POST /api/admin/etl/catalog-match` route runs the same match →
refresh → re-point pipeline on demand; the flow is idempotent (repeat
calls return zeros).

## Adding Code

When adding a new route, add only HTTP parsing, status mapping, OpenAPI
metadata, and DTO serialization to `routes/`. Put use-case behavior in a
service.

When adding a new upstream API call, put the transport client under
`clients/<vendor-or-api>/`. Convert upstream-specific responses into domain
or provider-neutral models at the adapter/service boundary.

When adding a new availability provider, add an adapter under
`service/availability/provider/adapters/<vendor>/` and wire it through the
provider registry. No route should branch on that vendor.

When adding an ETL source, add transform code under
`service/etl/vendors/<vendor>/` and pure DTOs under `models/` when they are
large, shared, or reused by tests.

When adding data access, put SQL and jOOQ in `repo/`. Routes and services call
repo methods rather than embedding persistence details.
