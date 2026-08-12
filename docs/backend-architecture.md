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

## Boundary Rules

These rules are stricter than package placement. They describe ownership.

### Routes

Routes are the HTTP shell. They parse request inputs, call a controller or
service, translate known errors into HTTP status codes, and return DTOs.
Routes do not construct persistence queries, instantiate repos for use-case
work, parse provider refs, branch on vendors, or coordinate multi-step
business workflows.

New behavior enters through a controller/service. If a route needs a read
facade, define that facade as service/controller code and keep repo access
behind it. Existing route-to-repo paths are tech debt; when one is touched,
move that path behind a service/controller instead of expanding it.

### Repos

Repos own SQL. If code needs SQL, jOOQ, table names, JSONB casts, materialized
view refreshes, link-table writes, or persistence mapping, put it behind a repo
method. Services and ETLs ask for capability through methods; they do not pass
`DSLContext` around to make their own queries.

Entity repos own the full persistence surface for their entity. A
`CampgroundRepo` owns campground-table reads, queries, writes, and link-table
maintenance that is part of campground persistence. A `CampsiteRepo` does the
same for campsites. Tesla Superchargers and Planet Fitness locations follow the
same pattern. Do not add a generic catalog writer that owns SQL for several
entity tables when the write belongs to an entity repo.

Cross-entity repos are allowed only when the query is genuinely a projection or
workflow over multiple owners. Name them after the read/use case
(`PoiServingRepo`, `CampsiteProviderRepo`), not as a generic
owner of another entity's table.

### Models

Model names must tell callers what kind of shape they are holding:

- **Table row models** use the singular entity name (`Campground`, `Campsite`,
  `TeslaSupercharger`, `PlanetFitnessLocation`) and map 1:1 to the table
  schema, including database-owned fields such as ids, timestamps, source
  columns, and soft-delete columns.
- **Repo projections** are named for their use, not the table
  (`PoiDetailRow`, `PoiSearchHit`). Put a
  projection in `models/` only when it crosses a repo boundary; otherwise keep
  it private to the repo.
- **ETL upsert candidates** are not table rows. Vendor ETLs emit candidate
  values such as `CampgroundUpsertCandidate` / `CampsiteUpsertCandidate`.
  Repos convert those candidates into persisted rows.

A model named after a table must not silently include provider-specific helper
fields, selected vendor refs, API response convenience fields, or partially
populated ETL input state. Those are separate projections or candidates.

## Package Patterns

Use stable package names for layers and generic extension points:

```
ca.floo.roadtrip
├── config/
├── model/
│   ├── api/
│   ├── availability/
│   ├── domain/
│   └── metadata/
├── repo/
├── client/
│   └── <vendor-or-api>/
├── service/
│   ├── api/
│   ├── availability/
│   │   ├── alert/
│   │   └── provider/
│   ├── etl/
│   │   ├── framework/
│   │   └── vendors/<vendor>/
│   ├── notification/
│   ├── ratelimit/
│   ├── routing/
│   └── scheduler/
└── route/
```

Package names are singular (`model/`, `client/`, `route/`); the layer tables
above name the layers in prose, not the directories.

Prefer these generic forms in docs and reviews:

- `client/<vendor-or-api>/*Client.kt` for outbound HTTP clients.
- `service/availability/provider/<Vendor>AvailabilityProvider.kt` for
  availability-provider adapters — they sit flat in that package, one file per
  vendor, beside the port they implement.
- `service/etl/vendors/<vendor>/*Etl.kt` for ETL transforms.
- `model/<area>/*Dto.kt` for API and upstream wire shapes.
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
```

The ETL framework owns orchestration and run lifecycle. Vendor ETLs parse,
validate, and transform their upstream inputs. Persistence stays in repos.

Each vendor ETL writes its own per-vendor campground/campsite rows keyed on
`data_provider`; nothing merges across vendors at write time or after import.
Duplicate real-world campgrounds from different vendors intentionally remain
separate catalog rows.

The read path serves campgrounds and campsites directly off the
`campgrounds` and `campsites` tables (`CampgroundRepo`, `CampsiteRepo`),
filtered on `deleted_at IS NULL`. There is no canonical/matching layer: an
earlier design collapsed cross-vendor duplicates into
`campground_canonical`/`campsite_canonical` materialized views, but
`V44__provider_model_cleanup.sql` dropped both when the provider model moved
to direct `data_provider`/`booking_provider` columns, and nothing recreates
them. Imports write straight to the base tables; there is no refresh step
between an import and the row becoming visible to reads.

## Adding Code

When adding a new route, add only HTTP parsing, status mapping, OpenAPI
metadata, and DTO serialization to `routes/`. Put use-case behavior in a
service.

When adding a new upstream API call, put the transport client under
`clients/<vendor-or-api>/`. Convert upstream-specific responses into domain
or provider-neutral models at the adapter/service boundary.

When adding a new availability provider, add an adapter in
`service/availability/provider/` and wire it through the provider list. No
route should branch on that vendor.

When adding an ETL source, add transform code under
`service/etl/vendors/<vendor>/` and pure DTOs under `models/` when they are
large, shared, or reused by tests.

Campground and campsite ETLs should stay pure: parse captured vendor payloads
into `ParseResult`, transform them into `TransformResult` records, and emit
`CampgroundUpsertCandidate` / `CampsiteUpsertCandidate` values directly. The
ETL orchestrator owns import-run lifecycle and batching; repos persist one
bounded batch through methods such as `CampgroundRepo.upsertCampgroundBatch`
and `CampsiteRepo.upsertCampsiteBatch`. Candidate values are not persisted
`Campground` or `Campsite` table rows; ids and timestamps are assigned by
persistence.
If an ETL flow needs to read existing campground/campsite rows or mutate their
relationships, add that read/write path to a repo instead of passing
`DSLContext` into vendor ETLs or embedding SQL in ETL adapters.

The same rule applies to other catalog entities. Tesla and Planet Fitness ETLs
emit `TeslaSuperchargerUpsertCandidate` and
`PlanetFitnessLocationUpsertCandidate`; persistence still goes through the
owning entity repo, not through a generic ETL-owned catalog writer.

When adding data access, put SQL and jOOQ in `repo/`. Routes and services call
repo methods rather than embedding persistence details.

## Migrations

When changing schema, add a new `V<next>__*.sql` under
`backend/src/main/resources/db/migration`. Never edit a versioned migration
master already carries — not even a comment. Flyway checksums the file's bytes,
so a rewrite strands every database that recorded the old checksum, and the
backend exits at boot with a mismatch on that version instead of serving.
Repeatable `R__` migrations are exempt: re-running when their checksum changes
is what they are for.

A stranded database is repaired, not un-edited — editing the file back only
strands the databases that agreed with it. That is how prod went down on
2026-08-12: it had recorded the checksum from one side of a comment rewrite,
and the revert flipped the mismatch onto it. The boot error names both values
(`applied` and `resolved`); write the resolved one into the history row:

```sh
docker compose exec -T postgres psql -U roadtrip -d roadtrip \
  -c "UPDATE flyway_schema_history SET checksum = <resolved> WHERE version = '<n>';"
```
