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
method. Services and ETLs ask for capability through methods; they never hold or
pass a `DSLContext`. A service that needs several writes to land together takes
a `UnitOfWork` (see Transactions); one that does not takes the repos it uses.

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
- **Typed JSONB columns.** When a JSONB column has more than one writer or
  more than one reader, its shape is a `@Serializable` domain type
  (`CampgroundLocation`, `CampgroundLink`, ...). Candidates and rows carry the
  type; the entity repo is the only place that encodes or decodes it. Vendor
  ETLs map upstream keys into the type, so the read path never carries
  per-vendor key fallbacks. `campsites.equipment`, `photos`, and `attributes` follow the same rule through `CatalogColumnJson`; `CampsiteRepo` is their only codec, and the API serves `CampsiteDto`, never the row.
  `campgrounds.booking_aliases` / `campsites.booking_aliases`
  (`BookingAlias(provider, ref)` — another vendor's identity for the same
  inventory, V59) are the same pattern again: `CampgroundRepo` / `CampsiteRepo`
  are the only codec, and `RefLinkRepo`'s ref lookups match the JSONB column
  alongside the row's own `booking_provider`/`booking_provider_ref` primary.
- **Vocabulary enums carry their own labels.** The campground bags
  (`amenities`, `cell_service`, `metadata`, `price`,
  `default_campsite_schedule`, `alerts`) and `campsites.kind` follow the same
  typed-JSONB rule: `@Serializable` domain models and enums
  (`AmenityKey`, `CampgroundAmenity`, `Carrier`, `CarrierSignal`,
  `CampgroundRating`, `CampgroundMetadata`, `CampgroundPrice`,
  `CampgroundSchedule`, `CampgroundAlert`, `CampsiteKind`) live in
  `model/domain`, `CatalogColumnJson` is the one codec, and `CampgroundRepo` /
  `CampsiteRepo` are the only encoders/decoders. Vendor ETLs map upstream keys
  and strings into the enums, one mapping table per vendor
  (`service/etl/framework/CampsiteKinds.kt` for campsite kind); a client never
  owns a translation table because the label rides on the enum itself
  (`AmenityKey.label`/`.negativeLabel`, `Carrier.label`, `CampsiteKind.label`).
  A migration that changes a stored shape (V57 for the campground bags, V58
  for `campsites.kind`) canonicalizes existing rows in place and never
  backfills; `make data-import` fills gaps by re-running the ETLs.

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

The tree above lives under `backend/src/main/kotlin`. A parallel
`backend/src/main/java` holds exactly one file, `HealthProbe.java` — a
deliberately zero-dependency container healthcheck probe. Application code
belongs in Kotlin.

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

## Transactions

One type opens a transaction that spans repos: `JooqUnitOfWork`, in `repo/`.
Everything above `repo/` asks it for one.

```kotlin
interface UnitOfWork {
    fun <T> run(block: (Repos) -> T): T
}
```

A service that must write atomically takes a `UnitOfWork` and calls `run`. The
block receives a `Repos` — one connection context's repo handles, built lazily
— and every repo it touches is on that transaction. The block returning commits;
anything thrown rolls the whole block back.

- Services never take a `DSLContext`. If a service needs one repo and no
  atomicity, inject that repo; if it needs several writes to land together,
  inject `UnitOfWork`.
- `run` is not reentrant: a nested `run` throws. jOOQ would give it a second
  pooled connection and an independent transaction rather than a savepoint —
  invisible to the outer block's uncommitted rows, and able to deadlock on
  them. A service already inside a block takes repo handles from that block's
  `Repos`, never a `UnitOfWork` of its own.
- `run` is blocking, and its block cannot suspend by construction (the block is
  a plain `(Repos) -> T`, so nothing can await inside a transaction). Call it
  from an IO dispatcher.
- A checked exception thrown by the block reaches the caller as itself.
  `JooqUnitOfWork` undoes jOOQ's `DataAccessException("Rollback caused")`
  wrapper, which would otherwise hand `org.jooq` to a service that took the
  port precisely so it never names one.
- A repo opens its own transaction only for statements it owns end to end — for
  example the legacy mirror in `UserBookingCredentialsRepo`,
  `UserSettingsRepo.saveNotifications`, the catalog batch upserts, and the
  availability poller/target statements that must land as one. It never opens
  one on a service's behalf; that is what `UnitOfWork` is for.
- `JooqUnitOfWork.autocommit` is the non-transactional handle bundle, for
  callers that batch without a transaction. The import is non-transactional by
  decision (`rfcs/0004-ingestion-controller.md`), so `repoModule` binds the
  `Repos` singleton its terminal sinks receive to `JooqUnitOfWork.autocommit`:
  the choice is visible at the wiring site rather than implied by which
  constructor was used.
- No type outside `repo/`, `db/`, and `di/InfraModule.kt` names `org.jooq` —
  exceptions included. A repo that can fail on a PostGIS fault translates
  `org.jooq.exception.DataAccessException` into a domain type
  (`CorridorUnavailableException`) or handles it, so no caller ever catches
  jOOQ's. The GEOS topology-fault predicate both sides share
  (`isTopologyFault`) therefore lives in `support/`, typed as `Throwable`.
  `LayeringGuardTest` fails the build on any other `org.jooq` reference.

## Application Wiring

The Ktor entrypoint should stay thin:

1. Load configuration and boot resources.
2. Install Ktor plugins.
3. Start runtime services and schedulers.
4. Register routes and static mounts.
5. Subscribe shutdown cleanup.

`repoModule` registers every repo as a singleton, plus `JooqUnitOfWork` and its
two projections: `UnitOfWork` for callers that transact and `Repos` for callers
that batch on the autocommit context. Route wiring resolves repos from there; it
does not construct them, and it holds no `DSLContext`.

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
`AvailabilityObservationBatch.scope` is the typed `BookingProviderRef` the adapter fetched under; adapters never put vendor ids in generic fields.

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

The two Aspira-backed campground ETLs join their leaves to a sibling geometry
feed by name; that join is registry-declared per row (`geometry:` in
`poi-registry.yaml`, typed and validated at boot), not inferred from input
slugs, and each emitted candidate records how its pin was found in the
`campgrounds.geometry_provenance` column.

A terminal ETL's sink factory is `(Repos) -> TerminalSink`: the binding is handed
repo handles, never a connection context, and production binds them to
`JooqUnitOfWork.autocommit`. A phase's counts cross back as
`ImportPhaseCounts`, a `@Serializable` model; `IngestRunRepo.completePhase`
encodes it into the `ingest_runs.counts` JSONB column, so no jOOQ type is an
interchange value between two service methods.

Building the `IngestController` single sweeps the ghost rows a mid-run restart
left behind: parent `ingest_runs` rows still `started` whose owning coroutine is
gone are marked aborted. How old a row must be to count as a ghost is
`roadtrip.ingest.stale-run-after` (default `30m`) — it has to clear the longest
phase a given catalog runs, so it moves with the catalog and the machine.

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

## The API Contract

`model/api/ApiContract.kt` declares every endpoint under `/api/**` and
`/auth/password/**` as data: its method (an `ApiMethod`, because `model/` never
names Ktor), its path as registered, its request DTO, and — as an `ApiBody`
apiece — the 2xx it answers with and every non-2xx body it can serialize, each
with its status. The statuses are `HTTP_*` constants in `model/api/ApiStatus.kt`
for the same reason `ApiMethod` exists. Four things read it.

`errors` has no default. It once defaulted to `400 ApiErrorSchema`, so a row
acquired a published 400 by saying nothing at all — and the contract is
one-directional, so nothing could fail a declared error no route can serve.
Every row now states its errors, `emptyList()` included, read off the handler:
`/api/build-info`, `/api/health` and `/api/me` take no body and no parameter and
throw nothing, so no `StatusPages` mapping can fire on them and they declare
nothing but their 200.

`requestRequired` is what the document publishes as `requestBody.required`. It
is `true` everywhere except the two rows whose handler reads the body through
`decodeOptionalTextJsonBody` and answers a blank one with a default — `PUT
/api/settings/notifications` and `POST /api/settings/notifications/slack/test`.
`ApiContractCoverageTest` pins that set and that a row with no request body never
carries a `false`.

`registerKoinRoutes` compares it against the live routing tree at boot, in both
directions, beside the RFC 0010 access guard. A mounted route with no row fails
the boot; a row with no route does too, unless the row is marked `conditional`
(the Slack interactivity endpoint, which mounts only when a signing secret is
configured). `/api/docs` is exempt: the Swagger UI subtree is framework-generated
and `openapi.json` answers with a Ktor type. `ApiContractCoverageTest` exercises
the comparator; the boot guard is what sees the real tree.

The DTO half of each row is held to the routes by `route/ContractBodyCheck.kt`, a
test-side plugin `routeTestApplication` installs. On every JSON response whose
matched route matches a row, it picks the class the row names for that status,
strict-decodes the body into it, re-encodes with `roadtripApiJson`, and compares
the two `JsonElement` trees. A row naming the wrong DTO, a DTO that drifted from
what the route serves, and a status neither the row nor the route's access level
declares are each rewritten to `599` carrying the drift as the message, which
fails the test that produced them whatever it was asserting. The strict decoder
is the one encoder with a single setting tightened —
`Json(roadtripApiJson) { ignoreUnknownKeys = false }`; a route serving a field
its DTO lacks is the drift being hunted. `Json*` fields compare as trees, so a
`JsonNull` inside one round-trips honestly. A response on a path no row names is
skipped, which is what covers `/test/**` and `/data/**`.

JSON means `application/json`, any `application/*+json` — `problem+json` and the
`geo+json` `RoadtripRouting` already configures gzip for — and `text/json`. On a
`(leaf, status)` whose row declares a *body*, a response the check cannot read as
JSON is drift rather than a skip: the row's claim is that the status carries that
DTO. A status the row declares body-less, or does not declare at all, is skipped,
which is what leaves Slack's empty `text/plain` acks alone.

The bodies a `StatusPages` handler writes are checked too — the `SettingsError`
vocabulary among them. Ktor catches a handler's throw on the engine call, which
carries no matched route, so the plugin stashes the matched leaf in the call's
attributes while still inside routing and reads it back when the response is
rendered. A plugin that refuses a call *before* routing binds a node leaves no
leaf to stash; a JSON response with none has its request's method and path
resolved against the contract's path templates instead, and is checked against
that row when exactly one matches.

`:backend:contractLedgerCheck` then holds the *suite* to the contract. The plugin
appends every checked `(method, path, status)` to
`backend/build/contract-ledger/exercised.tsv`, once per triple and kind, and
records a drift there too; the task fails on any recorded violation and on any
row with a success body that no test produced. `ContractBodyCheckTest` is the one
suite that drifts on purpose, so it installs the plugin as a fixture and both its
violations *and* its passes go under their own kinds — a fixture mounts a stub on
a live row's path, and counting its pass as coverage would call a row covered
that no real route test produces.

The ledger's first line carries a digest of `ApiContract.endpoints` — every row's
verb, path, declared statuses and classes, and `requestRequired`. The task
refuses a ledger whose digest does not match the contract on its own classpath
and says to rerun `:backend:test`, which is what stops a standalone
`./gradlew :backend:contractLedgerCheck` reporting green off a file written
against a different contract. On a green run it also prints every declared
`(method, path, status, class)` with a body that no test produced, and the count
in the summary line. That half is informational: the check is one-directional by
construction, so nothing can *fail* a declared response no route serves, and the
list is what says how much of the published document is a claim rather than a
verified fact.

The ledger is `:backend:test`'s declared output, so an up-to-date or cached test
run still has a current one to verify, and the task is a finalizer on
`:backend:test` as well as being named outright in `make test` and in CI's
`Run backend tests + coverage + contract checks` step.

`:backend:generateApiTypes` walks `serializer(kclass).descriptor` from every row,
transitively, and writes `frontend/src/api/generated/api-types.ts`;
`:backend:checkApiTypes` runs the same generator and fails when the committed file
differs. Both go through `main`'s runtime classpath, so both need the same compile
— and therefore the same Docker for `generateJooq` — that `:backend:test` needs.
They run in `make test` and in CI's `backend-tests` job, never in `gradle-lint`.

Optionality follows the one encoder (`route/common/RouteResponses.kt`:
`encodeDefaults = true`, `explicitNulls = false`), not the Kotlin type:

- **Response:** a field is optional exactly when it is nullable. A non-null field
  with a default is always sent, so it is required. `| null` is never generated,
  because a Kotlin null is never encoded; a `JsonNull` *value* inside a `Json*`
  field still is.
- **Request:** a field is optional when it is nullable **or** has a default.

A wire vocabulary is a `@Serializable enum` — `WatchStatus`, `WatchDoneReason`,
`RecgovSessionState`, `RecgovLoginStatus`, `BookingActionStatus` — so its
TypeScript union is generated with it. `WireVocabularyTest` pins each constant's
`@SerialName` against the strings already on the wire. `WatchStatus` and
`WatchDoneReason` are also `availability_watch` column values, so they carry a
`wireValue` the repos write and the test pins both copies against the same list;
the other three are wire-only and kotlinx encodes them from `@SerialName` alone.
A value class is generated as the one value it wraps.

Generation fails, by name, on: a sealed or polymorphic descriptor; a serial kind
or a `kotlinx.serialization.json` type with no mapping; a nullable list element or
map value, which `explicitNulls = false` would leave on the wire as a null the
TypeScript cannot honestly describe; a serial name that would not make a legal
type name; two classes that would generate the same name; and a class reached
from both a request root and a response root whose two optionality rules
disagree. Each is a build failure a human resolves, not a half-built variant.

The loop when you add or change an endpoint. "Its statuses" means every status
the handler can answer with a body, read off the handler — the body check fails
the first test that produces one the row does not declare, and the ledger gate
prints the ones you declared that no test produces. `errors` is mandatory:
`emptyList()` is the answer for a handler that cannot refuse. Set
`requestRequired = false` where the handler answers a blank body with a default:

```
add the route  ->  add the row with its statuses and requestRequired  ->  make api-types  ->  commit the diff
```

### `/api/docs`

`GET /api/docs/openapi.json` and the Swagger UI's own copy of the spec are built
in two halves, both through the same contract pass and the same
`roadtripApiJson`, so they are one document: `OpenApiSmokeTest` compares the two
whole. The UI copy additionally carries Ktor's empty `webhooks`, which
`OpenApiDocSource.Routing` puts on the model it hands `serializeModel` and
`explicitNulls = false` drops from the other.

The **routing tree** contributes the paths — spelled by
`RoutingNode.path(OpenApiRoutePathFormat)`, which is the same spelling
`ApiContract` uses — plus the tag, the summary and the description each route
declares with `describeApi(...)`, plus the path and query parameters Ktor infers
from the route selectors: a `{id}` segment becomes a `path` parameter and a
`param("x")` selector a `query` one. The contract pass overwrites only
`requestBody` and `responses`, so those inferred parameters survive onto the
served operation.

**The document therefore carries no query parameters at all.** This tree uses no
`param("x")` selectors — every handler reads `call.request.queryParameters[…]`
directly — so Ktor infers none, and neither `ApiEndpoint` nor `describeApi` has
anywhere to declare one. `/api/geocode` answers 400 without `q` and the document
does not say `q` exists, so `/api/docs` cannot be used to call most of the GET
surface. The path-parameter half does work, though `{id}` is typed `string` where
the handler parses a `Long`. Declaring query parameters on the row and merging
them into `Operation.parameters`, the way `requestBody` and `responses` are
merged, is backlogged on #754. The included surface is `isContractedPath` from
`route/common/RouteInventory.kt` plus `/test/**`: `/api/**` and
`/auth/password/**`, minus the whole `/api/docs/` subtree.

`apigen/OpenApiContractDocument.apply` contributes the rest. `components/schemas`
comes from one walk of the whole contract — the same `walkContract` the
TypeScript generator reads, cached per process — so a schema name and a generated
`interface` name are one declaration rendered twice. Each row then fills its
operation's `requestBody` (`application/json`, a `$ref`, and the row's own
`requestRequired` as `required`) and its `responses`: the success status, every
declared error status, and an empty response where the status carries no body.
Two classes at one status become a `oneOf`.

`401` and `403` are **not** on the rows where the tree already knows them.
`apiDocsRoutes` builds a lookup with `RoutingNode.declaredAccessByLeaf()` and
passes it in as `accessOf` — `apigen` may name Ktor but never `route/` — and the
builder turns each route's declared `RouteAccess` into responses through
`RouteAccess.guardBodies()`: `401` for `User`, both for `HasRole`, and neither
for `Anonymous`, `Signed` or `UserOrCapability`, none of which refuses anyone at
that layer. Where a *handler* answers a 401 or a 403 on such a route, the row
says so, and the two sets are merged by (status, schema) so nothing is published
twice. `ContractBodyCheck` reads the same `guardBodies()`, so the document and
the body check cannot disagree about which level refuses with what.

Schemas are rendered from the whole contract regardless of what is mounted, so
every `$ref` resolves even in a test that boots a slice of the tree. A row whose
operation is not mounted is skipped: the boot guard is the authority on a
non-`conditional` row with no route.

`additionalProperties: false` on a generated object schema is exact for a
response — the one encoder writes the declared elements and nothing else — and is
the contract's *statement* for a request, where `ignoreUnknownKeys = true` means
the decoder tolerates an extra key and promises nothing about it.
`io.ktor.openapi.JsonSchema` carries no `propertyNames`, so an enum-keyed map
publishes its value schema and not the union its keys are drawn from; the union
is still a component, because the walk reaches it. TypeScript can say it, and
says it as `Partial<Record<Union, V>>` — a bare `Record<Union, V>` is a mapped
type in which every member of the union is a *required* key, which a Kotlin
`Map<SomeEnum, V>` is not. No contracted DTO has one today.

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

The booking seam under `service/booking/` follows the same shape one layer
over: `BookingAdapter` is the port, and each vendor's adapter (e.g.
`RecGovBookingAdapter`) owns everything specific to it — its cart URL, its
`canFulfil` credential check, and its failure codes — behind
`BookingAdapterRegistry`. `BookingActionService` and
`WatchCapabilityService` ask the registry for the claiming adapter and return
what it says; they never hold a vendor constant, a vendor credential port, or
a vendor code table themselves. Adding a second booking vendor is a new
adapter file and a registry entry, not a sweep through those services.

`TenantRegistry` sits beside `BookingAdapterRegistry`: it is loaded from the
`booking_providers` section at boot and is the only place a vendor's or
tenant's display name and CTA verb live. Services ask it; routes never do —
the outcome or DTO a service returns already carries the name.

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

### Non-transactional migrations (`CONCURRENTLY`)

Postgres refuses `CREATE INDEX CONCURRENTLY` inside a transaction, so a
migration that builds one has to run outside Flyway's. Two settings make that
work, and neither fails loudly when it is dropped — the migration simply blocks
forever, so both are asserted by `FlywayConcurrentIndexConfigTest`.

**The `.sql.conf` sidecar.** A file named exactly after its migration plus
`.conf` (`V61__booking_alias_indexes.sql.conf`) holding:

```
executeInTransaction=false
```

Flyway does **not** checksum the sidecar, so a stale one produces no mismatch
and no warning — treat it as part of the migration and never edit it once the
migration is applied.

**The session lock.** Flyway's default PostgreSQL lock is *transactional*,
which leaves its own connection idle-in-transaction for the whole run;
`CREATE INDEX CONCURRENTLY` then waits on that virtualxid forever. The fix is
`flyway.postgresql.transactional.lock=false`, set at three sites that must stay
in step: `Db.kt`'s `flywaySessionLock` (the boot path), the `generateJooq`
Flyway in `backend/build.gradle.kts`, and that file's `flyway { }` block, whose
`pluginConfiguration` keys the Gradle plugin prefixes with `flyway.` itself.

**Recovery when V61 fails part-way.** A failed concurrent build leaves an
`INVALID` index behind, which the migration's `IF NOT EXISTS` rerun will *not*
rebuild. Drop it by hand before retrying:

```sh
psql -c "SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;"
psql -c "DROP INDEX CONCURRENTLY <name>;"   # once per name listed
./gradlew :backend:flywayRepair              # clear the failed history row
```

Then restart the backend so the boot migration reapplies V61.
