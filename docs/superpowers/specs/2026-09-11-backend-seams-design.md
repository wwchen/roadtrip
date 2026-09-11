# Backend seams: the unit of work and the HTTP shell (audit phase 5a)

Design for phase 5a of the 2026-09-09 architecture audit: finding 10
(issue #739, `DSLContext` as a structural parameter) and finding 13 (issue
#742, routes doing use-case work and route-level constants). Both are seams
between `route`, `service`, and `repo`, and both are fixed in the same DI
wiring, so they ship together.

## Problem

**Finding 10.** Seven production types outside `repo/` hold a `DSLContext`:
`AvailabilityWatchService`, `UserProvisioningService`, `PollerBackfill`,
`EtlOrchestrator`, `IngestController`, `TransactionalWatchAlertScope`, the
free function `sweepStaleIngestRuns`, and the ETL framework's sink factory
`(DSLContext) -> TerminalSink`. Only four sites transact, in three spellings
(`transactionResult { DSL.using(config) }`, `transactionResult { config.dsl() }`,
`transaction { DSL.using(config) }`); the rest construct repos from the context
and pass them around as arguments. `IngestController.runImport` returns
`org.jooq.JSONB` as the interchange type between two private methods.
`RouteCorridorService` and `PoisOnRouteService` catch
`org.jooq.exception.DataAccessException`. `RouteModule` injects the context and
rebuilds ten repos by hand that `RepoModule` already registers as singletons.
Three test files build a connectionless `DSL.using(SQLDialect.POSTGRES)` to
satisfy a repo constructor they then override completely.

**Finding 13.** `RouteRoutes` chains `RouteCache` and `RouteCorridorService`
and assembles the composite GeoJSON itself, including a Mapbox rule about
adjacent duplicate waypoints. `GeocodeRoutes` takes the `MapboxGeocoder`
client directly and performs the whole use case; there is no geocode service.
`CampsiteRoutes` pins a 30-per-minute IP rate limit in the route while the
bulk sibling reads its limit from `roadtrip.availability.bulk.ip-rate-limit-per-minute`
(default 10), so the costlier endpoint is the tunable one. `AuthRoutes` holds
`setOf("google-oauth2")`, a connection slug the claims dialect already knows
varies by environment. `mapAspiraUpstreamError` and its twin
`mapRecgovUpstreamError` return `Pair<HttpStatusCode, …>` (always 503), have
test-only callers, and are the only reason two provider files import `io.ktor`.
The five list-paging bounds are duplicated verbatim between the dashboard and
watch routes.

## Design

### The unit of work

```kotlin
// repo/UnitOfWork.kt — the port; no jOOQ type in its signature
interface UnitOfWork {
    fun <T> run(block: (Repos) -> T): T
}

// repo/Repos.kt — one connection context's repo handles, built lazily
class Repos internal constructor(private val txn: DSLContext) {
    val watches by lazy { AvailabilityWatchRepo(txn) }
    val pollers by lazy { AvailabilityPollerRepo(txn) }
    val users by lazy { UserRepo(txn) }
    val userIdentities by lazy { UserIdentityRepo(txn) }
    val campgrounds by lazy { CampgroundRepo(txn) }
    val campsites by lazy { CampsiteRepo(txn) }
    val teslaSuperchargers by lazy { TeslaSuperchargerRepo(txn) }
    val planetFitnessLocations by lazy { PlanetFitnessLocationRepo(txn) }
    val importRuns by lazy { ImportRunRepo(txn) }
    val ingestRuns by lazy { IngestRunRepo(txn) }
}

// repo/JooqUnitOfWork.kt — the one place a transaction is opened
class JooqUnitOfWork(private val ctx: DSLContext) : UnitOfWork {
    override fun <T> run(block: (Repos) -> T): T =
        ctx.transactionResult { config -> block(Repos(config.dsl())) }
    val autocommit: Repos = Repos(ctx)
}
```

`Repos` lists only the repos a transactional or ETL path needs today; a new
member is one lazy line. `JooqUnitOfWork.autocommit` is the non-transactional
handle bundle for callers that batch without a transaction (the ETL sinks, per
RFC 0004). DI registers `single<UnitOfWork> { JooqUnitOfWork(get()) }` and
`single<Repos> { get<JooqUnitOfWork>().autocommit }` in `RepoModule`.

`docs/backend-architecture.md` gains a "Transactions" section: services open a
transaction only through `UnitOfWork.run`; the block receives `Repos`; repos
never open transactions except the single-repo mirrors that already do
(`UserBookingCredentialsRepo`); no type outside `repo/`, `db/`, and the two
infrastructure DI modules names `org.jooq`.

### Who changes

| type | before | after |
| --- | --- | --- |
| `AvailabilityWatchService` | `ctx: DSLContext`, three `transactionResult` blocks building `AvailabilityWatchRepo` and `TransactionalWatchAlertScope(txn)` | `unitOfWork: UnitOfWork`; `run { repos -> repos.watches…; RepoWatchAlertScope(repos.pollers) }` |
| `WatchAlertScope` | `TransactionalWatchAlertScope(txn: DSLContext)` | `RepoWatchAlertScope(pollerRepo: AvailabilityPollerRepo)`; the KDoc's claim becomes true |
| `UserProvisioningService` | `ctx`, one `transactionResult`, repos threaded through six helpers | `unitOfWork`; helpers take `Repos` or the two repos as today, built once from the block |
| `PollerBackfill` | `ctx` for reads and a per-watch `transaction` | `watchRepo`, `pollerRepo` injected for the read pass; `unitOfWork.run { membership.sync(w, it.pollers, …) }` per watch |
| `EtlOrchestrator` | `ctx`, `ImportRunRepo(ctx)`, default `productionEtlRegistry(ctx)` | `importRunRepo: ImportRunRepo`, `etlRegistry` required (DI passes `productionEtlRegistry(get<Repos>())`) |
| `TerminalEtlDefinition` | `sinkFactory: (DSLContext) -> TerminalSink`, `bind(ctx)` | `sinkFactory: (Repos) -> TerminalSink`, `bind(repos)`; the four factories read `repos.campgrounds` etc. |
| `IngestController` | `ctx`, builds two repos, `runImport(): JSONB` | `ingestRunRepo`, `adminIngestReadRepo` injected; `runImport(): ImportPhaseCounts` (a `@Serializable` model in `model/domain/etl`), `IngestRunRepo.completePhase(phaseId, counts)` encodes JSONB inside the repo |
| `sweepStaleIngestRuns(ctx)` | free function over a context | `IngestRunRepo.abortStaleStartedRows(staleAfter)` called from the DI boot hook with the repo singleton; `BootRecovery.kt` deleted |
| `RouteCorridorService`, `PoisOnRouteService` | catch `org.jooq.exception.DataAccessException` | `RouteCorridorRepo` throws `CorridorUnavailableException` (domain, in `model/domain/routing`); the POIs-on-route repo returns an empty result on a PostGIS topology fault and logs it; the services import nothing from `org.jooq` |
| `RouteModule` | `val ctx: DSLContext by inject()` and ~10 hand-built repos | `get<XRepo>()` everywhere; `RepoModule` also registers `UserIdentityRepo`, `UserSessionRepo`, `ImportRunRepo`, `IngestRunRepo` |

After this, `grep -rn "org.jooq" backend/src/main/kotlin` matches only
`repo/`, `db/`, `di/InfraModule.kt`, and `di/RepoModule.kt`. A layering guard
test (`backend/src/test/kotlin/ca/floo/roadtrip/LayeringGuardTest.kt`) reads
the main source tree and fails on any other `org.jooq` import, on any
`io.ktor` import under `service/` other than the URL builders in
`service/auth/OidcIdentityProvider.kt`, and on any `ca.floo.roadtrip.repo`
import under `route/`.

Tests that fake a repo by subclassing it over a detached context keep working
(the repo constructors are unchanged) and are out of scope; repo interfaces are
a later change if wanted.

### The HTTP shell

- **Routing.** A new `service/routing/RoutePlanService(routeCache, corridorService)`
  with `plan(waypoints: List<LngLat>, corridorRadiusMiles: Double?): RoutePlan`
  (`directions`, `corridorGeoJson?`). The adjacent-duplicate-waypoint rule
  moves into the service as `RoutingException(DUPLICATE_WAYPOINTS)`; the route
  keeps only string parsing and per-point range validation. The composite
  `FeatureCollection` is built by `service/api/RouteResponseMapper` from
  `RoutePlan`; the corridor geometry is carried as a parsed `JsonElement`
  rather than re-parsed from text. `RouteRoutes` takes `RoutePlanService`,
  `RouteResponseMapper`, and `RouteConfig`.
- **Geocoding.** A new `service/geocode/GeocodeService(geocoder: MapboxGeocoder)`
  owning availability (`configured`), `MAX_QUERY_LENGTH`, the limit bounds, the
  proximity parse, the upstream call, and `GeocodeUnavailable` mapping; it
  returns `GeocodeResponseDto`. `GeocodeRoutes` takes the service, parses the
  query string, and maps the service's outcomes to statuses. `GeocodeService`
  is the only place `client/mapbox` is imported outside `di/`.
- **Rate limit.** `AvailabilityConfig` gains `campsite: CampsiteAvailabilityConfig(ipRateLimitPerMinute)`
  read from `roadtrip.availability.campsite.ip-rate-limit-per-minute`
  (default 30, validated `>= 1`, same shape as the bulk config).
  `campsiteRoutes` takes the config and its default limiter reads it;
  `application.yaml` carries the key beside the bulk one.
- **Auth connections.** `AuthConfig.allowedConnections: Set<String>` from
  `roadtrip.auth.allowed-connections` (default `["google-oauth2"]`);
  `AuthRoutes` reads it from the wiring bundle.
- **Dead mappers.** `mapAspiraUpstreamError` and `mapRecgovUpstreamError` are
  deleted with their `io.ktor` imports; their tests assert
  `upstreamAvailabilityError` (the production path) instead.
- **List paging bounds.** One `route/common/ListPaging` object (default,
  min, max limit; default and min offset) replaces the two duplicated sets;
  the dashboard's snapshot limits stay with the dashboard. Path segments,
  header names, error codes, and cookie names stay where they are.

## Decisions

- **A concrete `Repos` class, not an interface.** The tests that need fakes
  already subclass repos; a `Repos` interface would only add a second thing
  to fake. If repo ports arrive later, `Repos` becomes their holder.
- **`autocommit` is explicit.** RFC 0004 keeps the import non-transactional;
  the ETL sinks get their repos from `JooqUnitOfWork.autocommit`, so the
  choice is visible at the call site rather than implied by which constructor
  was used.
- **The settings services stay as they are.** `UserSettingsService` writes
  three tables without a transaction; `docs/reservation-providers.md` says
  "in the same transaction", which is only true of the repo's own mirror. The
  doc sentence is corrected; making the three-table save atomic is a separate
  change.
- **`OidcIdentityProvider` keeps `io.ktor.http.URLBuilder`.** It builds a
  redirect URL; it does not serve HTTP. The guard test allowlists that file.
- **No migration, no wire change.** Every endpoint's request and response
  shape is unchanged; only the config keys are new, both with defaults equal
  to today's literals.

## Out of scope

- Making the import transactional (RFC 0004 future work) and the settings
  three-table save atomic.
- Repo interfaces and retiring the detached-context test fakes.
- The remaining route literals that are not policy (path segments, cookie
  names), `LOGIN_FLOW_MAX_AGE_SECONDS`, `RECENT_RUNS_LIMIT`.

## Testing

- `JooqUnitOfWorkTest` (real DB): a block that throws rolls back its writes;
  a block that returns commits; `autocommit` writes are visible immediately.
- `AvailabilityWatchServiceTest`, `UserProvisioningServiceTest`,
  `PollerBackfillTest`, `EtlOrchestratorCampflareTest`, `IngestControllerTest`,
  `AdminIngestRoutesTest`, `AvailabilityWatchRoutesTest`, `AuthRoutesTest`,
  `AuthControllerTest`: construct the new shapes; behaviour unchanged.
- `IngestRunRepoTest`: `completePhase` round-trips `ImportPhaseCounts`.
- `RouteCorridorRepoTest` / `PoisOnRouteServiceTest`: the translated
  exception and the topology-fault empty result.
- `RoutePlanServiceTest`: duplicate adjacent waypoints refuse before any
  upstream call; corridor requested and not; `RouteResponseMapperTest` pins
  the FeatureCollection shape byte-for-byte against today's route output.
- `GeocodeServiceTest`: unconfigured, over-long query, limit clamping,
  proximity parse, upstream failure; `GeocodeRoutesTest` unchanged statuses.
- `CampsiteRoutesTest`: the limiter reads the config; `RoadtripRuntimeConfigTest`:
  both new keys, defaults, and the `>= 1` check.
- `LayeringGuardTest` as described.
- Live on the local stack: `/api/route` with and without `corridor`,
  `/api/geocode`, a watch create/update/delete, an admin import run;
  `make qa`.

## Docs

`docs/backend-architecture.md` (Transactions section; Application Wiring
names `UnitOfWork`/`Repos`; the ETL flow's sink factory), `AGENTS.md` (one
line: services take `UnitOfWork` or repos, never `DSLContext`),
`docs/reservation-providers.md` (the "same transaction" sentence),
`rfcs/0004-ingestion-controller.md` (a decision-log line: sinks bind to
`Repos`; import still non-transactional), `docs/adding-a-reservation-provider.md`
if it names `mapAspiraUpstreamError`.
