# Backend Seams: the Unit of Work and the HTTP Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** No production type outside `repo/`, `db/`, and the infrastructure DI modules names `org.jooq`; every transaction is opened in one place (`JooqUnitOfWork.run`); and `/api/route`, `/api/geocode`, the campsite rate limit, and the auth connection allowlist stop doing use-case work and holding policy constants in route files.

**Architecture:** A `UnitOfWork` port hands a block a `Repos` bundle of repo handles bound to one connection context; `JooqUnitOfWork` is the only `transactionResult` call site and also exposes a non-transactional `autocommit` bundle for the ETL sinks. Services and the ETL framework take `UnitOfWork` or plain repo singletons instead of a `DSLContext`; the two repos that leaked `org.jooq.exception.DataAccessException` translate it at the repo boundary. On the HTTP side a new `RoutePlanService` + `RouteResponseMapper` and a new `GeocodeService` take the use-case work out of `RouteRoutes` and `GeocodeRoutes`, two route literals become config keys with today's values as defaults, and a source-scanning `LayeringGuardTest` ratchets all of it shut.

**Tech Stack:** Kotlin 2 / Ktor / Koin / jOOQ + Postgres (Testcontainers via `SharedDbTest`) / kotlinx.serialization / JUnit 5 + kotlin.test / `io.ktor:ktor-client-mock`.

**Spec:** `docs/superpowers/specs/2026-09-11-backend-seams-design.md`. Audit findings 10 and 13 in `docs/superpowers/specs/2026-09-09-architecture-audit.md`. Issues #739 and #742. Follows phase 4b, `docs/superpowers/plans/2026-09-10-tenant-registry.md`.

## Resolutions

Where the spec was silent or its letter fought the repo's own conventions, this plan decided. Each decision below is load-bearing for the task that implements it.

1. **`CorridorUnavailableException` lives in `support/`, not `model/domain/routing/`.** The spec says "domain, in `model/domain/routing`", but every one of the sixteen exception types in this backend lives in `ca.floo.roadtrip.support` (`RoutingException`, `GeocodeException`, `AspiraException`, …) and `model/` holds data shapes only. A lone exception under `model/` would be the first of its kind and would sit oddly against the `models -> stdlib + serialization only` rule. Placement changes; the spec's intent (the repo throws a domain type, the service imports nothing from `org.jooq`) is unchanged. Task 4.
2. **`RoutePlanService` returns a sealed `RoutePlanResult`, not `RoutingException(DUPLICATE_WAYPOINTS)`.** `/api/route` has three distinct failure shapes today — 400 `duplicate_adjacent` with a per-index detail, 503 `routing_unavailable`, 503 `corridor_unavailable` — and `RoutingException` carries only a message. Encoding a code plus an index into that message and switching on it in the route is stringly-typed dispatch; re-shaping `RoutingException` would touch five unrelated call sites, including `MapboxDirections`. A sealed result is what this repo already does for exactly this (`AvailabilityWatchControllerResult`, `ForcePollerResult`, `RouteBodyResult`, `SiteTypeQuery`), and it is the only option that keeps the wire byte-identical. Task 5.
3. **`RoutePlan` carries `waypoints` and `corridorRadiusMiles` as well as `directions` and `corridorGeoJson`.** The spec names two fields; the mapper needs all four to rebuild today's `properties.waypoints` array and the corridor's `radius_miles`. Task 5.
4. **`PoiServingRepo` moves from `ServiceModule` to `RepoModule`.** It is the last reason `di/ServiceModule.kt` imports `org.jooq.DSLContext`, and the spec's allowlist names only `di/InfraModule.kt` and `di/RepoModule.kt`. It is a repo; it belongs in the repo module. Its `enabledDataProviders` argument comes from `get<AppConfig>()`, which Koin resolves across modules. Task 1.
5. **`RouteCorridorService` becomes `internal open class` with an `open` method.** `RoutePlanServiceTest` needs a corridor that answers without PostGIS. `UserRepo`, `AvailabilityWatchRepo`, and `UserSettingsRepo` are already `open` for the same reason. One keyword, no behaviour change. Task 5.
6. **The topology-fault classifier is a named `internal fun isTopologyFault(e: DataAccessException)` in `PoiServingRepo.kt`.** Provoking a real GEOS `TopologyException` from a test polygon is not deterministic across GEOS versions; a named predicate is unit-testable against a synthesized cause chain, and `PoiServingRepoTest` still asserts end-to-end that a polygon PostGIS cannot parse fails loudly rather than returning empty. Task 4.
7. **`GeocodeService` returns a sealed `GeocodeOutcome`; the response mapper moves with it.** The spec says the route "maps the service's outcomes to statuses", so the outcomes are a type. `geocodeResponseDto` moves from `route/api/geocode/GeocodeRoutes.kt` to `service/geocode/GeocodeService.kt` so the service can return a built `GeocodeResponseDto`; the two existing `GeocodeRoutesTest` cases move to `GeocodeServiceTest` with only their import changed. Task 6.
8. **`docs/adding-a-reservation-provider.md` needs no edit.** The spec hedged ("if it names `mapAspiraUpstreamError`"). It does not; `grep` over `docs/` and `rfcs/` finds the two mappers named nowhere outside the spec itself. Task 8 skips it.
9. **`AuthConfig.allowedConnections` is the last constructor parameter and defaults to `setOf("google-oauth2")`.** `AuthRoutesTest` builds an `AuthConfig` by named argument; a defaulted trailing parameter keeps that fixture compiling and keeps the default equal to today's literal. `AuthRouteWiring.allowedConnections` has no default — the wiring bundle is composition code and should state it. Task 7.
10. **The boot recovery sweep becomes a private helper in `di/InfraModule.kt`.** The spec says `BootRecovery.kt` is deleted and the sweep is "called from the DI boot hook with the repo singleton". Its logger and its 30-minute threshold go with it as a file-private `const`/`val` beside the module's other private constants. Task 3.

## Global Constraints

- **No migration in this phase.** `V61__booking_alias_indexes.sql` is the highest applied migration and stays the highest. Nothing here adds, edits, or replays a Flyway migration. Never edit an applied migration.
- **No wire change.** Every endpoint's request and response shape is byte-identical after this plan. The only new things on the wire's edges are two config keys, both defaulting to the literal they replace (`30` for the campsite IP rate limit, `["google-oauth2"]` for the auth connection allowlist). **Before `RouteRoutes` is refactored, Task 5 Step 1 pins `/api/route`'s composite `FeatureCollection` with a byte-for-byte string assertion against today's code**; that same string is the assertion the new `RouteResponseMapperTest` carries afterwards.
- **The allowed `org.jooq` locations, after this plan:** `backend/src/main/kotlin/ca/floo/roadtrip/repo/`, `backend/src/main/kotlin/ca/floo/roadtrip/db/`, `backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt`, and `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt`. Nothing else in `backend/src/main/kotlin` may contain `import org.jooq`. `LayeringGuardTest` enforces it.
- **The `io.ktor` allowlist under `service/`:** exactly one file, `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/OidcIdentityProvider.kt`, which imports `io.ktor.http.URLBuilder` and `io.ktor.http.Url` to build a redirect URL and serves no HTTP. No other file under `service/` may contain `import io.ktor`. `LayeringGuardTest` enforces it.
- **No `ca.floo.roadtrip.repo` import under `route/`.** True today; `LayeringGuardTest` makes it a ratchet.
- **Layering rules, verbatim from `AGENTS.md`:**
  - Prefer typed Kotlin/Java DTOs (`@Serializable` data classes or existing schema classes) for request/response bodies. Do not hand-build JSON strings in routes when a DTO can represent the shape.
  - SQL, jOOQ DSL queries, table references, and persistence mapping belong in `repo` classes only. Routes and services call repo methods rather than embedding SQL.
  - Layering is `routes -> service -> repo`: routes are the HTTP shell (parse inputs, call a service/controller, set status codes, return DTOs) and do not add new route-to-repo paths. When an existing route-to-repo path is touched, move it behind a service/controller instead of expanding it.
  - Keep business logic out of routes; put orchestration in `service` and persistence in `repo`.
- **No inline magic constants.** Every numeric, string, and duration literal introduced here is a named `const val` (or config with a code default): the boot-recovery threshold, the geocode limit bounds and max query length, the paging bounds, the rate limits, the guard test's path prefixes.
- **Comments short and rare.** Keep the existing KDoc where its claim stays true (and Task 2 makes `WatchAlertScope`'s KDoc true for the first time); do not narrate the refactor in comments.
- **Repo test fakes that subclass a repo over a detached `DSL.using(SQLDialect.POSTGRES)` keep working and are out of scope** (`route/auth/AuthRoutesTest.kt`, `service/settings/UserSettingsServiceTest.kt`, `service/settings/RecGovCredentialServiceTest.kt`). Repo constructors are unchanged throughout.
- Backend gate, run at the end of every task: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q` (Docker must be running for Testcontainers).
- One commit per task, conventional prefix, `Refs #739` (finding 10) or `Refs #742` (finding 13), trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- A hook denies the first Write/Edit to each file per session: state the facts it asks for in one line and re-issue the identical call. Never run `git stash`.

---

### Task 1: The unit of work and the repo singletons it needs

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/UnitOfWork.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/repo/Repos.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/repo/JooqUnitOfWork.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/repo/JooqUnitOfWorkTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt` (add `UserIdentityRepo`, `UserSessionRepo`, `ImportRunRepo`, `IngestRunRepo`, `PoiServingRepo`, and the three unit-of-work singles), `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt` (delete the `PoiServingRepo` single and its `PoiServingRepo` import; the `org.jooq.DSLContext` import stays until Task 2)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/JooqUnitOfWorkTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/di/ServiceModuleWiringTest.kt` (unchanged source; it must stay green because it resolves the real `repoModule` + `serviceModule` graph)

**Interfaces:**
- Consumes: `org.jooq.DSLContext` and `DSLContext.transactionResult`; the existing repo constructors, all of which take `(ctx: DSLContext)` as their first parameter and are unchanged by this task; `ca.floo.roadtrip.config.AppConfig.readPathProviders.enabledDataProviders: Set<String>`.
- Produces:
  - `interface UnitOfWork { fun <T> run(block: (Repos) -> T): T }` in `ca.floo.roadtrip.repo`
  - `class Repos internal constructor(private val txn: DSLContext)` with lazy `val`s: `watches: AvailabilityWatchRepo`, `pollers: AvailabilityPollerRepo`, `users: UserRepo`, `userIdentities: UserIdentityRepo`, `campgrounds: CampgroundRepo`, `campsites: CampsiteRepo`, `teslaSuperchargers: TeslaSuperchargerRepo`, `planetFitnessLocations: PlanetFitnessLocationRepo`, `importRuns: ImportRunRepo`, `ingestRuns: IngestRunRepo`
  - `class JooqUnitOfWork(private val ctx: DSLContext) : UnitOfWork` with `override fun <T> run(block: (Repos) -> T): T` and `val autocommit: Repos`
  - Koin singles resolvable after this task: `JooqUnitOfWork`, `UnitOfWork`, `Repos`, `UserIdentityRepo`, `UserSessionRepo`, `ImportRunRepo`, `IngestRunRepo`, `PoiServingRepo`

- [ ] **Step 1: Write the failing `JooqUnitOfWorkTest`**

Create `backend/src/test/kotlin/ca/floo/roadtrip/repo/JooqUnitOfWorkTest.kt`:

```kotlin
package ca.floo.roadtrip.repo

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val TRANSACTIONAL_SOURCE = "uow-transactional"
private const val ROLLED_BACK_SOURCE = "uow-rolled-back"
private const val AUTOCOMMIT_SOURCE = "uow-autocommit"
private const val SEEN_COUNT = 7

/**
 * The one place a transaction is opened. A block that throws must leave no row
 * behind; the autocommit bundle must leave one immediately.
 */
class JooqUnitOfWorkTest : SharedDbTest() {
    private val unitOfWork by lazy { JooqUnitOfWork(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM import_runs")
    }

    @Test
    fun `a block that returns commits its writes`() {
        val runId = unitOfWork.run { repos -> repos.importRuns.start(TRANSACTIONAL_SOURCE) }

        assertEquals("started", statusOf(runId))
    }

    @Test
    fun `a block that throws rolls its writes back`() {
        assertFailsWith<IllegalStateException> {
            unitOfWork.run { repos ->
                repos.importRuns.start(ROLLED_BACK_SOURCE)
                error("the use case failed after writing")
            }
        }

        assertNull(idOf(ROLLED_BACK_SOURCE), "a failed block must leave no import_runs row")
    }

    @Test
    fun `every repo handle in one block shares the transaction`() {
        assertFailsWith<IllegalStateException> {
            unitOfWork.run { repos ->
                val id = repos.importRuns.start(ROLLED_BACK_SOURCE)
                repos.importRuns.complete(id, seenCount = SEEN_COUNT)
                error("the use case failed after two repo calls")
            }
        }

        assertNull(idOf(ROLLED_BACK_SOURCE), "both writes must roll back together")
    }

    @Test
    fun `autocommit writes are visible immediately, outside any transaction`() {
        val runId = unitOfWork.autocommit.importRuns.start(AUTOCOMMIT_SOURCE)

        assertNotNull(idOf(AUTOCOMMIT_SOURCE))
        assertEquals("started", statusOf(runId))
    }

    @Test
    fun `the autocommit bundle is one instance, and its handles are lazy singletons`() {
        assertEquals(unitOfWork.autocommit, unitOfWork.autocommit)
        assertEquals(unitOfWork.autocommit.importRuns, unitOfWork.autocommit.importRuns)
    }

    private fun statusOf(runId: Long): String? =
        ctx.fetchOne("SELECT status FROM import_runs WHERE id = ?", runId)?.get("status", String::class.java)

    private fun idOf(source: String): Long? =
        ctx.fetchOne("SELECT id FROM import_runs WHERE source = ?", source)?.get("id", Long::class.java)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :backend:test --tests '*JooqUnitOfWorkTest*' --offline -q`
Expected: FAIL to compile — `Unresolved reference: JooqUnitOfWork`.

- [ ] **Step 3: Write the port**

Create `backend/src/main/kotlin/ca/floo/roadtrip/repo/UnitOfWork.kt`:

```kotlin
package ca.floo.roadtrip.repo

/**
 * A unit of work over the catalog. [run] hands [block] repo handles that all
 * share one connection context and commits when it returns normally; anything
 * thrown rolls the whole block back.
 *
 * No jOOQ type appears here on purpose: a service takes this port, never a
 * `DSLContext`, so "which connection am I on" stops being a service concern.
 */
interface UnitOfWork {
    fun <T> run(block: (Repos) -> T): T
}
```

- [ ] **Step 4: Write the repo bundle**

Create `backend/src/main/kotlin/ca/floo/roadtrip/repo/Repos.kt`:

```kotlin
package ca.floo.roadtrip.repo

import org.jooq.DSLContext

/**
 * One connection context's repo handles, built on first use. Lists only the
 * repos a transactional or ETL path needs today; a new member is one lazy line.
 *
 * Constructed only by [JooqUnitOfWork], so a caller cannot bind a bundle to a
 * context of its own choosing.
 */
class Repos internal constructor(
    private val txn: DSLContext,
) {
    val watches: AvailabilityWatchRepo by lazy { AvailabilityWatchRepo(txn) }
    val pollers: AvailabilityPollerRepo by lazy { AvailabilityPollerRepo(txn) }
    val users: UserRepo by lazy { UserRepo(txn) }
    val userIdentities: UserIdentityRepo by lazy { UserIdentityRepo(txn) }
    val campgrounds: CampgroundRepo by lazy { CampgroundRepo(txn) }
    val campsites: CampsiteRepo by lazy { CampsiteRepo(txn) }
    val teslaSuperchargers: TeslaSuperchargerRepo by lazy { TeslaSuperchargerRepo(txn) }
    val planetFitnessLocations: PlanetFitnessLocationRepo by lazy { PlanetFitnessLocationRepo(txn) }
    val importRuns: ImportRunRepo by lazy { ImportRunRepo(txn) }
    val ingestRuns: IngestRunRepo by lazy { IngestRunRepo(txn) }
}
```

- [ ] **Step 5: Write the one transaction site**

Create `backend/src/main/kotlin/ca/floo/roadtrip/repo/JooqUnitOfWork.kt`:

```kotlin
package ca.floo.roadtrip.repo

import org.jooq.DSLContext

/**
 * The only `transactionResult` call site in the backend.
 *
 * [autocommit] is the non-transactional bundle, bound to the pool's own
 * autocommit context. The import is non-transactional by decision (RFC 0004),
 * so its sinks take this explicitly rather than inheriting it from whichever
 * constructor they happened to be handed.
 */
class JooqUnitOfWork(
    private val ctx: DSLContext,
) : UnitOfWork {
    override fun <T> run(block: (Repos) -> T): T = ctx.transactionResult { config -> block(Repos(config.dsl())) }

    val autocommit: Repos = Repos(ctx)
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :backend:test --tests '*JooqUnitOfWorkTest*' --offline -q`
Expected: PASS (5 tests).

- [ ] **Step 7: Register the unit of work and the five missing repos**

Replace the whole of `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt` with:

```kotlin
package ca.floo.roadtrip.di

import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.repo.AdminIngestReadRepo
import ca.floo.roadtrip.repo.ApiCacheRepo
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.DatabaseHealthRepo
import ca.floo.roadtrip.repo.ImportRunRepo
import ca.floo.roadtrip.repo.IngestRunRepo
import ca.floo.roadtrip.repo.JooqUnitOfWork
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RefLinkRepo
import ca.floo.roadtrip.repo.Repos
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.UnitOfWork
import ca.floo.roadtrip.repo.UserBookingCredentialsRepo
import ca.floo.roadtrip.repo.UserIdentityRepo
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSessionRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.ref.DbRefResolver
import ca.floo.roadtrip.service.ref.RefResolver
import org.koin.dsl.module

val repoModule =
    module {
        single { CampsiteRepo(get()) }
        single { PoiRepo(get()) }
        single { RefLinkRepo(get()) }
        single<RefResolver> { DbRefResolver(get()) }
        single { AvailabilityRepo(get()) }
        single { AvailabilityWatchRepo(get()) }
        single { AvailabilityPollerRepo(get()) }
        single { AvailabilityRunRepo(get()) }
        single { AvailabilityFetchCallRepo(get()) }
        single { AvailabilityWatchTargetRepo(get()) }
        single { ApiCacheRepo(get()) }
        single { CampgroundRepo(get()) }
        single { TeslaSuperchargerRepo(get()) }
        single { PlanetFitnessLocationRepo(get()) }
        single { RouteCorridorRepo(get()) }
        single { AdminIngestReadRepo(get()) }
        single { UserRepo(get()) }
        single { UserIdentityRepo(get()) }
        single { UserSessionRepo(get()) }
        single { UserSettingsRepo(get()) }
        single { UserBookingCredentialsRepo(get()) }
        single { DatabaseHealthRepo(get()) }
        single { ImportRunRepo(get()) }
        single { IngestRunRepo(get()) }
        single {
            PoiServingRepo(
                ctx = get(),
                enabledDataProviders = get<AppConfig>().readPathProviders.enabledDataProviders,
            )
        }

        single { JooqUnitOfWork(get()) }
        single<UnitOfWork> { get<JooqUnitOfWork>() }
        single<Repos> { get<JooqUnitOfWork>().autocommit }
    }
```

- [ ] **Step 8: Drop the duplicate `PoiServingRepo` single from `ServiceModule`**

In `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, delete this block:

```kotlin
        single {
            val config: AppConfig = get()
            PoiServingRepo(
                ctx = get<DSLContext>(),
                enabledDataProviders = config.readPathProviders.enabledDataProviders,
            )
        }
```

and delete the now-unused `import ca.floo.roadtrip.repo.PoiServingRepo` line. Leave `import org.jooq.DSLContext` in place — `AvailabilityWatchService` and `PollerBackfill` still use it, and Task 2 removes it.

- [ ] **Step 9: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. `ServiceModuleWiringTest` still resolves `UserSettingsService`, `RecGovCredentialService`, and `CompanionChannel` from the real modules — `PoiServingRepo` now comes from `repoModule`, which that test already loads.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/JooqUnitOfWorkTest.kt
git commit -m "$(cat <<'EOF'
feat(repo): a unit of work and the repo singletons it needs

Refs #739

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: The three transacting services move onto the unit of work

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/WatchAlertScope.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/PollerBackfill.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningServiceTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/AuthControllerTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/PollerBackfillTest.kt`

**Interfaces:**
- Consumes: `UnitOfWork.run(block: (Repos) -> T): T`, `JooqUnitOfWork(ctx: DSLContext)`, `Repos.watches`, `Repos.pollers`, `Repos.users`, `Repos.userIdentities` (Task 1). `AvailabilityPollerMembership.sync(watch: AvailabilityWatchRepo.Watch, repo: AvailabilityPollerRepo, tighterCadencePull: OffsetDateTime?)` and `AvailabilityWatchRepo.list(status: WatchStatus?, limit: Int)` and `AvailabilityPollerRepo.pollerIdsForWatch(watchId: Long): Set<Long>` are unchanged.
- Produces:
  - `internal class AvailabilityWatchService(private val unitOfWork: UnitOfWork, private val alertProviders: AlertProviderRegistry, private val capabilityValidator: WatchCapabilityValidator, private val lifecycleNotifications: WatchLifecycleNotifications)` — `create`, `update`, `delete`, `deleteReturningSnapshot` signatures unchanged
  - `internal class RepoWatchAlertScope(override val pollerRepo: AvailabilityPollerRepo) : WatchAlertScope` — replaces `TransactionalWatchAlertScope`
  - `class UserProvisioningService(private val unitOfWork: UnitOfWork, private val roleGrants: Map<Role, Set<String>> = emptyMap())` — `provision(provider: String, claims: IdentityClaims): UserId` unchanged
  - `internal class PollerBackfill(private val watchRepo: AvailabilityWatchRepo, private val pollerRepo: AvailabilityPollerRepo, private val unitOfWork: UnitOfWork, private val membership: AvailabilityPollerMembership)` — `run()` unchanged
  - `private fun authRouteWiring(ctx: DSLContext, unitOfWork: UnitOfWork, config: AppConfig): AuthRouteWiring?` in `RouteModule` (the `ctx` parameter survives this task and dies in Task 4)

- [ ] **Step 1: Point the existing service tests at the new constructors**

These are behaviour-preserving edits: same assertions, new construction. In `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt`, add the import

```kotlin
import ca.floo.roadtrip.repo.JooqUnitOfWork
```

and replace the two construction sites. The positional one:

```kotlin
        return AvailabilityWatchService(JooqUnitOfWork(ctx), providers, capabilityValidator, lifecycleNotifications)
```

and the named one, whose first argument becomes:

```kotlin
        return AvailabilityWatchService(
            unitOfWork = JooqUnitOfWork(ctx),
            alertProviders = providers,
```

In `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt`, add the same import and change all three `ctx = ctx,` arguments inside `AvailabilityWatchService(` to `unitOfWork = JooqUnitOfWork(ctx),`.

In `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningServiceTest.kt`, add the import and change the factory:

```kotlin
    private fun provisioningWith(roleGrants: Map<Role, Set<String>>) = UserProvisioningService(JooqUnitOfWork(ctx), roleGrants)
```

In `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/AuthControllerTest.kt`, add the import and change:

```kotlin
            userProvisioningService = UserProvisioningService(JooqUnitOfWork(ctx)),
```

In `backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt`, add the import and change:

```kotlin
            userProvisioningService = UserProvisioningService(JooqUnitOfWork(detachedCtx)),
```

(The detached context is never used: `/api/me` resolves through the stub session service and never calls `provision`.)

In `backend/src/test/kotlin/ca/floo/roadtrip/service/scheduler/PollerBackfillTest.kt`, add the imports

```kotlin
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.JooqUnitOfWork
```

(keep the `AvailabilityPollerRepo` import that is already there) and change the construction:

```kotlin
        val backfill =
            PollerBackfill(
                watchRepo = AvailabilityWatchRepo(ctx),
                pollerRepo = AvailabilityPollerRepo(ctx),
                unitOfWork = JooqUnitOfWork(ctx),
                membership = membership(),
            )
```

- [ ] **Step 2: Add the rollback test that proves the watch write and its poller links share one transaction**

Append to `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt`, inside the class. It reuses the file's own `service(...)` factory, `poiInput(...)`, `seedPoi(...)` and `createForTest(...)` helpers, and passes a capability validator that refuses *after* the repo write — so the watch row and its poller links must both disappear:

```kotlin
    @Test
    fun `a refusal after the watch row is written leaves neither the watch nor its links`() {
        val poiId = seedPoi("232447")
        val refusing = service(capabilityValidator = WatchCapabilityValidator { error("refused after the write") })

        assertFailsWith<IllegalStateException> { refusing.createForTest(poiInput(poiId)) }

        assertEquals(0, countOf("availability_watch"))
        assertEquals(0, countOf("availability_watch_poller"))
    }

    private fun countOf(table: String): Int =
        ctx.fetchOne("SELECT count(*) AS n FROM $table")!!.get("n", Int::class.java)
```

Every name used here — `service`, `WatchCapabilityValidator`, `seedPoi`, `poiInput`, `createForTest`, `assertFailsWith`, `assertEquals` — is already declared or imported in this file; the test adds no imports. `$table` is interpolated from a test-local literal, never from input.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :backend:test --tests '*AvailabilityWatchServiceTest*' --tests '*PollerBackfillTest*' --tests '*UserProvisioningServiceTest*' --offline -q`
Expected: FAIL to compile — `No value passed for parameter 'ctx'` / `Cannot find a parameter with this name: unitOfWork`.

- [ ] **Step 4: Make the alert scope hold a repo instead of a context**

Replace the whole of `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/WatchAlertScope.kt` with:

```kotlin
package ca.floo.roadtrip.service.availability.alert

import ca.floo.roadtrip.repo.AvailabilityPollerRepo

/**
 * The watch-write transaction as an alert provider sees it: repo handles already
 * bound to it, and no jOOQ type on the port. A vendor-hosted provider that only
 * calls an upstream API can ignore the whole scope.
 */
internal interface WatchAlertScope {
    /** Poller bookkeeping for this transaction. Never opens a connection of its own. */
    val pollerRepo: AvailabilityPollerRepo
}

internal class RepoWatchAlertScope(
    override val pollerRepo: AvailabilityPollerRepo,
) : WatchAlertScope
```

- [ ] **Step 5: Move `AvailabilityWatchService` onto the unit of work**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt`, replace these imports

```kotlin
import ca.floo.roadtrip.service.availability.alert.TransactionalWatchAlertScope
import org.jooq.DSLContext
import org.jooq.impl.DSL
```

with

```kotlin
import ca.floo.roadtrip.repo.UnitOfWork
import ca.floo.roadtrip.service.availability.alert.RepoWatchAlertScope
```

change the constructor's first parameter

```kotlin
internal class AvailabilityWatchService(
    private val unitOfWork: UnitOfWork,
```

and replace the three transaction bodies. `create`:

```kotlin
        val watch =
            unitOfWork.run { repos ->
                val input =
                    AvailabilityWatchRepo.CreateInput(
                        ownerUserId = ownerUserId.value,
                        targets = targets,
                        campsiteFilters = campsiteFilters,
                        startDate = startDate,
                        endDate = endDate,
                        cadenceSec = cadenceSec,
                        triggerKinds = triggerKinds,
                        triggerConfig = triggerConfig,
                        stopWhenTriggered = stopWhenTriggered,
                    )
                WatchTriggerConfig.validateCreate(input)
                val created = repos.watches.create(input)
                capabilityValidator.validate(created)
                alertProviders.forWatch(created).onWatchActivated(RepoWatchAlertScope(repos.pollers), created)
                created
            }
```

`update`:

```kotlin
        val update =
            unitOfWork.run { repos ->
                val input =
                    AvailabilityWatchRepo.UpdateInput(
                        targets = targets,
                        campsiteFilters = campsiteFilters,
                        startDate = startDate,
                        endDate = endDate,
                        cadenceSec = cadenceSec,
                        triggerKinds = triggerKinds,
                        triggerConfig = triggerConfig,
                        stopWhenTriggered = stopWhenTriggered,
                        status = status,
                    )
                WatchTriggerConfig.validateUpdate(input)
                val triggerIntentTouched = input.triggerKinds != null || input.triggerConfig != null
                val repo = repos.watches
                val before = repo.findById(id) ?: return@run null
                val updated = repo.update(id, input) ?: return@run null
                if (triggerIntentTouched) WatchTriggerConfig.validateSnapshot(updated)
                capabilityValidator.validate(updated)
                // ACTIVE -> the alert provider (re)subscribes / re-syncs poller links;
                // any non-ACTIVE status is a deactivate as far as opening-detection
                // is concerned -- the watch holds no live subscription.
                val provider = alertProviders.forWatch(updated)
                val scope = RepoWatchAlertScope(repos.pollers)
                if (updated.status == WatchStatus.ACTIVE) {
                    provider.onWatchActivated(scope, updated)
                } else {
                    provider.onWatchDeactivated(scope, updated)
                }
                before to updated
            }
```

`deleteReturningSnapshot`:

```kotlin
        val snapshot =
            unitOfWork.run { repos ->
                val repo = repos.watches
                // Snapshot pre-delete so the alert provider's deactivate hook has a
                // Watch to work with -- the row itself is about to disappear (FK
                // cascade will drop its availability_watch_poller links).
                val existing = repo.findById(id) ?: return@run null
                val deleted = repo.delete(id)
                if (deleted) {
                    alertProviders.forWatch(existing).onWatchDeactivated(RepoWatchAlertScope(repos.pollers), existing)
                    existing
                } else {
                    null
                }
            }
```

- [ ] **Step 6: Move `UserProvisioningService` onto the unit of work**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningService.kt`, replace `import org.jooq.DSLContext` with `import ca.floo.roadtrip.repo.UnitOfWork`, change the constructor's first parameter to `private val unitOfWork: UnitOfWork,`, and replace the opening of `provision`:

```kotlin
    fun provision(
        provider: String,
        claims: IdentityClaims,
    ): UserId =
        unitOfWork.run { repos ->
            val userRepo = repos.users
            val userIdentityRepo = repos.userIdentities
```

Everything from `// The common path:` down to the closing `}` of the block is unchanged: the six helpers keep taking `UserRepo` / `UserIdentityRepo` arguments and are not touched.

- [ ] **Step 7: Give `PollerBackfill` its read repos and the unit of work**

Replace the class body in `backend/src/main/kotlin/ca/floo/roadtrip/service/scheduler/PollerBackfill.kt` (the file KDoc above it is unchanged), and replace `import org.jooq.DSLContext` / `import org.jooq.impl.DSL` with `import ca.floo.roadtrip.repo.UnitOfWork`:

```kotlin
internal class PollerBackfill(
    private val watchRepo: AvailabilityWatchRepo,
    private val pollerRepo: AvailabilityPollerRepo,
    private val unitOfWork: UnitOfWork,
    private val membership: AvailabilityPollerMembership,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run() {
        val active = watchRepo.list(status = WatchStatus.ACTIVE, limit = BACKFILL_BATCH_LIMIT)
        if (active.size == BACKFILL_BATCH_LIMIT) {
            // We hit the cap: any active watches beyond this page get no poller
            // membership until their next mutation. Surface the gap instead of
            // silently under-linking. (Paginate here if this ever fires in prod.)
            log.warn("poller backfill hit the {}-watch cap; watches beyond it stay unlinked until edited", BACKFILL_BATCH_LIMIT)
        }
        var filled = 0
        for (w in active) {
            if (pollerRepo.pollerIdsForWatch(w.id).isNotEmpty()) continue
            unitOfWork.run { repos ->
                membership.sync(w, repos.pollers, tighterCadencePull = OffsetDateTime.now())
            }
            filled++
        }
        if (filled > 0) log.info("poller backfill linked {} active watches", filled)
    }
}
```

- [ ] **Step 8: Rewire the two DI modules**

In `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, add `import ca.floo.roadtrip.repo.UnitOfWork`, delete `import org.jooq.DSLContext` (this was its last use), and change the two definitions:

```kotlin
        single {
            AvailabilityWatchService(
                unitOfWork = get<UnitOfWork>(),
                alertProviders = get<AlertProviderRegistry>(),
                capabilityValidator = get<WatchTriggerCapabilityValidator>(),
                lifecycleNotifications =
                    DispatchingWatchLifecycleNotifications(
                        dispatcher = get<WatchAlertDispatcher>(),
                        scope = get<CoroutineScope>(),
                    ),
            )
        }
```

```kotlin
        single(createdAtStart = true) {
            PollerBackfill(
                watchRepo = get<AvailabilityWatchRepo>(),
                pollerRepo = get<AvailabilityPollerRepo>(),
                unitOfWork = get<UnitOfWork>(),
                membership = get<AvailabilityPollerMembership>(),
            ).also { it.run() }
        }
```

In `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, add `import ca.floo.roadtrip.repo.UnitOfWork`, add the injection beside the others in `registerKoinRoutes`

```kotlin
    val unitOfWork: UnitOfWork by inject()
```

change the call site

```kotlin
    val authWiring = authRouteWiring(ctx, unitOfWork, config)
```

and the helper's signature and the one line inside it that used `ctx` for provisioning:

```kotlin
private fun authRouteWiring(
    ctx: DSLContext,
    unitOfWork: UnitOfWork,
    config: AppConfig,
): AuthRouteWiring? {
```

```kotlin
                userProvisioningService = UserProvisioningService(unitOfWork, authConfig.roleGrants),
```

`ctx` still builds `UserRepo(ctx)` and `UserSessionRepo(ctx)` here; Task 4 replaces both with singletons and drops the parameter.

- [ ] **Step 9: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. `grep -rn "org.jooq" backend/src/main/kotlin/ca/floo/roadtrip/service` now matches only the four ETL-framework files and `PoisOnRouteService`/`RouteCorridorService`, which Tasks 3 and 4 take.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service backend/src/main/kotlin/ca/floo/roadtrip/di backend/src/test/kotlin/ca/floo/roadtrip
git commit -m "$(cat <<'EOF'
refactor(service): watches, provisioning and backfill take a unit of work

Refs #739

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: The ETL framework binds to `Repos`, and `JSONB` stops being an interchange type

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/etl/ImportPhaseCounts.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/repo/IngestRunRepoTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/TerminalEtlBinding.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/IngestController.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/repo/IngestRunRepo.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/BootRecovery.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/IngestRunRepoTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/IngestControllerTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestratorCampflareTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/AdminIngestRoutesTest.kt`

**Interfaces:**
- Consumes: `Repos.campgrounds`, `Repos.campsites`, `Repos.teslaSuperchargers`, `Repos.planetFitnessLocations`, `JooqUnitOfWork.autocommit: Repos` (Task 1). Unchanged: `CampgroundRepo.upsertCampgroundBatch(records): Int`, `CampsiteRepo.upsertCampsiteBatch(records): Pair<Int, Int>`, `TeslaSuperchargerRepo.upsertTeslaSuperchargerBatch(records): Int`, `PlanetFitnessLocationRepo.upsertPlanetFitnessLocationBatch(records): Int`, `ImportRunRepo.start/complete/fail`, `AdminIngestReadRepo.listRecent/runDetail/statusByTarget`.
- Produces:
  - `@Serializable data class ImportPhaseCounts(@SerialName("import_run_id") val importRunId: Long, val seen: Int, val swept: Int, @SerialName("terminal_etl") val terminalEtl: String, @SerialName("upserted_campsites") val upsertedCampsites: Int? = null, @SerialName("skipped_campsites") val skippedCampsites: Int? = null)` in `ca.floo.roadtrip.model.domain.etl`
  - `fun IngestRunRepo.completePhase(phaseId: Long, counts: ImportPhaseCounts)` — same name, new parameter type; the `org.jooq.JSONB` encode moves inside
  - `internal data class TerminalEtlDefinition<DTO, OUT>(val etl: SourceEtl<DTO, OUT>, private val sinkFactory: (Repos) -> TerminalSink<OUT>)` with `fun bind(repos: Repos): TerminalEtlBinding<DTO, OUT>`
  - `internal fun productionEtlRegistry(repos: Repos): Map<String, TerminalEtlBinding<*, *>>`
  - `open class EtlOrchestrator(private val importRunRepo: ImportRunRepo, private val rawDir: File, private val poiRegistry: PoiRegistry, private val staticDir: File, private val etlRegistry: Map<String, TerminalEtlBinding<*, *>>)` — `etlRegistry` is required, `runPoiData(name): Stats` and `runCampsiteData(name): CampsiteStats` unchanged
  - `class IngestController(private val ingestRunRepo: IngestRunRepo, private val adminReadRepo: AdminIngestReadRepo, val etl: EtlOrchestrator, private val importTargets: Map<String, Target>, private val metrics: RoadtripMetrics = RoadtripMetrics.NoOp, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO)` — every public method unchanged
  - `sweepStaleIngestRuns` no longer exists; `IngestRunRepo.abortStaleStartedRows(staleAfter: Duration): Int` is called from `infraModule`

- [ ] **Step 1: Write the failing `IngestRunRepoTest`**

This is the test that owns `completePhase`'s JSONB shape and the boot sweep. Create `backend/src/test/kotlin/ca/floo/roadtrip/repo/IngestRunRepoTest.kt`:

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.etl.ImportPhaseCounts
import ca.floo.roadtrip.model.metadata.ingest.Phase
import ca.floo.roadtrip.model.metadata.ingest.RunKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TARGET = "recgov"
private const val TRIGGERED_BY = "admin-api"
private const val IMPORT_RUN_ID = 4321L
private const val SEEN = 120
private const val SWEPT = 3
private const val TERMINAL_ETL = "recgov-campsites"
private const val UPSERTED_CAMPSITES = 99
private const val SKIPPED_CAMPSITES = 2
private val STALE_AFTER: Duration = Duration.ofMinutes(30)

class IngestRunRepoTest : SharedDbTest() {
    private val repo by lazy { IngestRunRepo(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM ingest_runs")
    }

    @Test
    fun `completePhase writes the counts the dashboard reads`() {
        val parentId = repo.createParentRow(TARGET, RunKind.IMPORT, TRIGGERED_BY)
        val phaseId = repo.createPhaseRow(parentId, TARGET, Phase.Import("import:$TARGET", TARGET))

        repo.completePhase(
            phaseId,
            ImportPhaseCounts(
                importRunId = IMPORT_RUN_ID,
                seen = SEEN,
                swept = SWEPT,
                terminalEtl = TERMINAL_ETL,
                upsertedCampsites = UPSERTED_CAMPSITES,
                skippedCampsites = SKIPPED_CAMPSITES,
            ),
        )

        val counts = countsOf(phaseId).jsonObject
        assertEquals("completed", statusOf(phaseId))
        assertEquals(IMPORT_RUN_ID, counts["import_run_id"]!!.jsonPrimitive.long)
        assertEquals(SEEN, counts["seen"]!!.jsonPrimitive.int)
        assertEquals(SWEPT, counts["swept"]!!.jsonPrimitive.int)
        assertEquals(TERMINAL_ETL, counts["terminal_etl"]!!.jsonPrimitive.content)
        assertEquals(UPSERTED_CAMPSITES, counts["upserted_campsites"]!!.jsonPrimitive.int)
        assertEquals(SKIPPED_CAMPSITES, counts["skipped_campsites"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an absent campsite count is omitted, not written as null`() {
        val parentId = repo.createParentRow(TARGET, RunKind.IMPORT, TRIGGERED_BY)
        val phaseId = repo.createPhaseRow(parentId, TARGET, Phase.Import("import:$TARGET", TARGET))

        repo.completePhase(
            phaseId,
            ImportPhaseCounts(
                importRunId = IMPORT_RUN_ID,
                seen = SEEN,
                swept = SWEPT,
                terminalEtl = TERMINAL_ETL,
            ),
        )

        val counts = countsOf(phaseId).jsonObject
        assertNull(counts["upserted_campsites"], "a POI_DATA phase must not ship a null campsite count")
        assertNull(counts["skipped_campsites"])
    }

    @Test
    fun `boot recovery marks stale started rows as aborted and leaves fresh ones alone`() {
        val staleId = startedRowAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
        val recentId = startedRowAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))

        assertEquals(1, repo.abortStaleStartedRows(STALE_AFTER))

        assertEquals("aborted", statusOf(staleId))
        assertNotNull(completedAtOf(staleId))
        assertTrue(notesOf(staleId)!!.contains("boot recovery"))
        assertEquals("started", statusOf(recentId), "rows younger than the cutoff must be untouched")
    }

    private fun startedRowAt(startedAt: OffsetDateTime): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO ingest_runs (target, phase, phase_kind, status, started_at, triggered_by)
                VALUES (?, 'import', 'target', 'started', ?, ?)
                RETURNING id
                """.trimIndent(),
                TARGET,
                startedAt,
                TRIGGERED_BY,
            )!!.get("id", Long::class.java)

    private fun countsOf(id: Long) =
        Json.parseToJsonElement(
            ctx.fetchOne("SELECT counts::text AS counts FROM ingest_runs WHERE id = ?", id)!!.get("counts", String::class.java),
        )

    private fun statusOf(id: Long): String? =
        ctx.fetchOne("SELECT status FROM ingest_runs WHERE id = ?", id)?.get("status", String::class.java)

    private fun notesOf(id: Long): String? =
        ctx.fetchOne("SELECT notes FROM ingest_runs WHERE id = ?", id)?.get("notes", String::class.java)

    private fun completedAtOf(id: Long): OffsetDateTime? =
        ctx.fetchOne("SELECT completed_at FROM ingest_runs WHERE id = ?", id)?.get("completed_at", OffsetDateTime::class.java)
}
```

- [ ] **Step 2: Delete the sweep test that just moved**

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/IngestControllerTest.kt`, delete the whole `boot recovery marks stale started rows as aborted` test (it now lives in `IngestRunRepoTest`, asserting the same three things), plus the now-unused `sweepStaleIngestRuns` import and any `INGEST_RUNS` / `ZoneOffset` / `assertNotNull` imports that only that test used. Compile errors name them.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :backend:test --tests '*IngestRunRepoTest*' --offline -q`
Expected: FAIL to compile — `Unresolved reference: ImportPhaseCounts`.

- [ ] **Step 4: Write the counts model**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/etl/ImportPhaseCounts.kt`:

```kotlin
package ca.floo.roadtrip.model.domain.etl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Counts written into `ingest_runs.counts` for one import phase. Section-specific
 * fields are nullable; readers ignore the ones they don't care about. Existing
 * dashboards keyed off `seen`/`swept`/`import_run_id` keep working.
 */
@Serializable
data class ImportPhaseCounts(
    @SerialName("import_run_id") val importRunId: Long,
    val seen: Int,
    val swept: Int,
    @SerialName("terminal_etl") val terminalEtl: String,
    @SerialName("upserted_campsites") val upsertedCampsites: Int? = null,
    @SerialName("skipped_campsites") val skippedCampsites: Int? = null,
)
```

- [ ] **Step 5: Encode the JSONB inside the repo**

In `backend/src/main/kotlin/ca/floo/roadtrip/repo/IngestRunRepo.kt`, add the imports

```kotlin
import ca.floo.roadtrip.model.domain.etl.ImportPhaseCounts
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
```

add the file-private encoder above the class (the same two settings the controller used, so the column bytes are unchanged):

```kotlin
// encodeDefaults + explicitNulls=false: a POI_DATA phase omits the campsite
// counts entirely rather than writing nulls readers have to skip.
@OptIn(ExperimentalSerializationApi::class)
private val ingestCountsJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }
```

and replace `completePhase`:

```kotlin
    fun completePhase(
        phaseId: Long,
        counts: ImportPhaseCounts,
    ) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "completed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(INGEST_RUNS.COUNTS, JSONB.valueOf(ingestCountsJson.encodeToString(counts)))
            .where(INGEST_RUNS.ID.eq(phaseId))
            .execute()
    }
```

`import org.jooq.JSONB` stays — this is a repo, which is where jOOQ belongs.

- [ ] **Step 6: Run the repo test to verify it passes**

Run: `./gradlew :backend:test --tests '*IngestRunRepoTest*' --offline -q`
Expected: FAIL to compile in `IngestController.kt` — `Type mismatch: inferred type is JSONB but ImportPhaseCounts was expected`. That is the next step's work; the repo itself is done.

- [ ] **Step 7: Bind terminal ETL sinks to `Repos`**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/TerminalEtlBinding.kt`, replace `import org.jooq.DSLContext` with `import ca.floo.roadtrip.repo.Repos` and replace the definition at the bottom:

```kotlin
@Suppress("DataClassContainsFunctions")
internal data class TerminalEtlDefinition<DTO, OUT>(
    val etl: SourceEtl<DTO, OUT>,
    private val sinkFactory: (Repos) -> TerminalSink<OUT>,
) {
    fun bind(repos: Repos): TerminalEtlBinding<DTO, OUT> = TerminalEtlBinding(etl, sinkFactory(repos))
}
```

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/ProductionTerminalEtlRegistry.kt`, replace `import org.jooq.DSLContext` with `import ca.floo.roadtrip.repo.Repos`, delete the four now-unused repo imports (`CampgroundRepo`, `CampsiteRepo`, `PlanetFitnessLocationRepo`, `TeslaSuperchargerRepo`), change the entry point:

```kotlin
internal fun productionEtlRegistry(repos: Repos): Map<String, TerminalEtlBinding<*, *>> =
    productionTerminalEtlDefinitions.mapValues { (_, definition) -> definition.bind(repos) }
```

and rewrite the four sink factories at the bottom of the file:

```kotlin
private fun <DTO> campgroundSink(etl: SourceEtl<DTO, CampgroundUpsertCandidate>): TerminalEtlDefinition<DTO, CampgroundUpsertCandidate> =
    TerminalEtlDefinition(etl) { repos ->
        terminalSink { records -> FlushCounts(upserted = repos.campgrounds.upsertCampgroundBatch(records)) }
    }

private fun <DTO> campsiteSink(etl: SourceEtl<DTO, CampsiteUpsertCandidate>): TerminalEtlDefinition<DTO, CampsiteUpsertCandidate> =
    TerminalEtlDefinition(etl) { repos ->
        terminalSink { records ->
            val (upserted, skipped) = repos.campsites.upsertCampsiteBatch(records)
            FlushCounts(upserted = upserted, skipped = skipped)
        }
    }

private fun <DTO> teslaSuperchargerSink(
    etl: SourceEtl<DTO, TeslaSuperchargerUpsertCandidate>,
): TerminalEtlDefinition<DTO, TeslaSuperchargerUpsertCandidate> =
    TerminalEtlDefinition(etl) { repos ->
        terminalSink { records -> FlushCounts(upserted = repos.teslaSuperchargers.upsertTeslaSuperchargerBatch(records)) }
    }

private fun <DTO> planetFitnessSink(
    etl: SourceEtl<DTO, PlanetFitnessLocationUpsertCandidate>,
): TerminalEtlDefinition<DTO, PlanetFitnessLocationUpsertCandidate> =
    TerminalEtlDefinition(etl) { repos ->
        terminalSink { records -> FlushCounts(upserted = repos.planetFitnessLocations.upsertPlanetFitnessLocationBatch(records)) }
    }
```

`Repos`' handles are lazy, so a sink that is never flushed still constructs no repo.

- [ ] **Step 8: Give `EtlOrchestrator` its repo and a required registry**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestrator.kt`, delete `import org.jooq.DSLContext`, and replace the constructor and the line under it:

```kotlin
open class EtlOrchestrator(
    private val importRunRepo: ImportRunRepo,
    private val rawDir: File,
    private val poiRegistry: PoiRegistry,
    /**
     * Base directory for registry paths such as data_source output_dir_prefix.
     */
    private val staticDir: File,
    /** Terminal ETL binding map keyed by YAML slug. */
    private val etlRegistry: Map<String, TerminalEtlBinding<*, *>>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rawCaptureStore = RawCaptureStore(rawDir = rawDir, staticDir = staticDir)
```

(the `private val importRunRepo = ImportRunRepo(ctx)` line is gone — the repo is now a parameter). Nothing else in the file changes: `runTerminal` already calls `importRunRepo.start/complete/fail`.

- [ ] **Step 9: Give `IngestController` its repos and a typed phase result**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/IngestController.kt`:

Replace these imports

```kotlin
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import org.jooq.JSONB
```

with

```kotlin
import ca.floo.roadtrip.model.domain.etl.ImportPhaseCounts
```

Delete the `ingestControllerJson` value and its `@OptIn` annotation, and delete the whole private `ImportPhaseCountsDto` data class at the bottom of the file (the model replaces it verbatim).

Replace the constructor's head:

```kotlin
class IngestController(
    private val ingestRunRepo: IngestRunRepo,
    private val adminReadRepo: AdminIngestReadRepo,
    val etl: EtlOrchestrator,
    private val importTargets: Map<String, Target>,
    private val metrics: RoadtripMetrics = RoadtripMetrics.NoOp,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(javaClass)
```

(the two `private val ingestRunRepo = ...` / `private val adminReadRepo = ...` lines are gone).

Replace `runImport`:

```kotlin
    private suspend fun runImport(phase: Phase.Import): ImportPhaseCounts =
        withContext(ioDispatcher) {
            when (phase.section) {
                Phase.Import.Section.POI_DATA -> {
                    val stats = etl.runPoiData(phase.name)
                    ImportPhaseCounts(
                        importRunId = stats.upsertResult.runId,
                        seen = stats.upsertResult.seenCount,
                        swept = stats.upsertResult.sweptCount,
                        terminalEtl = stats.terminalEtlSlug,
                    )
                }
                Phase.Import.Section.CAMPSITE_DATA -> {
                    val stats = etl.runCampsiteData(phase.name)
                    ImportPhaseCounts(
                        importRunId = stats.runId,
                        seen = stats.parsed,
                        swept = stats.swept,
                        terminalEtl = stats.terminalEtlSlug,
                        upsertedCampsites = stats.upserted,
                        skippedCampsites = stats.skipped,
                    )
                }
            }
        }
```

`runPhases` is unchanged: `ingestRunRepo.completePhase(phaseId, counts)` now type-checks against the model.

- [ ] **Step 10: Delete `BootRecovery.kt` and move its sweep into the boot hook**

```bash
git rm backend/src/main/kotlin/ca/floo/roadtrip/service/etl/framework/BootRecovery.kt
```

In `backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt`, replace the import

```kotlin
import ca.floo.roadtrip.service.etl.framework.sweepStaleIngestRuns
```

with

```kotlin
import ca.floo.roadtrip.repo.IngestRunRepo
import ca.floo.roadtrip.repo.Repos
import ca.floo.roadtrip.service.etl.framework.productionEtlRegistry
import org.slf4j.LoggerFactory
import java.time.Duration
```

add these two file-private declarations beside the existing private constants at the top:

```kotlin
// A restart mid-run leaves parent ingest_runs rows 'started' forever — the
// IngestController coroutine that owned them is gone. Well beyond the longest
// expected phase (the rec.gov enricher tops out near 10 min today).
private val STALE_INGEST_RUN_AFTER: Duration = Duration.ofMinutes(30)

private val bootRecoveryLog = LoggerFactory.getLogger("ca.floo.roadtrip.di.BootRecovery")
```

replace the `IngestController` single:

```kotlin
        single {
            val staticDir: File = get(named("staticDir"))
            sweepStaleIngestRunsAtBoot(get<IngestRunRepo>())
            IngestController(
                ingestRunRepo = get(),
                adminReadRepo = get(),
                etl =
                    EtlOrchestrator(
                        importRunRepo = get(),
                        rawDir = staticDir.resolveConfiguredPath(RAW_DATA_DIR),
                        poiRegistry = get(),
                        staticDir = staticDir,
                        etlRegistry = productionEtlRegistry(get<Repos>()),
                    ),
                importTargets = importTargetsFromRegistry(get()),
                metrics = get<RoadtripMetrics>(),
            )
        }
```

and add the helper beside `File.resolveConfiguredPath` at the bottom of the file:

```kotlin
/** Sweeps the ghost rows a mid-run restart left behind. Touches only ingest_runs;
 *  a partially upserted phase is mark-and-sweep's problem (RFC 0004 edge case #2). */
private fun sweepStaleIngestRunsAtBoot(ingestRunRepo: IngestRunRepo) {
    val swept = ingestRunRepo.abortStaleStartedRows(STALE_INGEST_RUN_AFTER)
    if (swept > 0) bootRecoveryLog.info("boot recovery: marked {} ingest_runs rows as aborted", swept)
}
```

`import org.jooq.DSLContext` stays in this file for `single<DSLContext> { dsl(get<DataSource>()) }` — one of the four allowed locations.

- [ ] **Step 11: Point the three ETL test files at the new constructors**

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/EtlOrchestratorCampflareTest.kt`, add

```kotlin
import ca.floo.roadtrip.repo.ImportRunRepo
import ca.floo.roadtrip.repo.JooqUnitOfWork
```

and replace all three `EtlOrchestrator(` blocks with the same shape (they differ only in the raw payloads written before them):

```kotlin
        val orchestrator =
            EtlOrchestrator(
                importRunRepo = ImportRunRepo(ctx),
                rawDir = rawDir,
                poiRegistry = registry(),
                staticDir = staticDir,
                etlRegistry = productionEtlRegistry(JooqUnitOfWork(ctx).autocommit),
            )
```

In `backend/src/test/kotlin/ca/floo/roadtrip/service/etl/framework/IngestControllerTest.kt`, add `import ca.floo.roadtrip.repo.AdminIngestReadRepo` and `import ca.floo.roadtrip.repo.ImportRunRepo`, and replace `controllerWith`:

```kotlin
    private fun controllerWith(
        targets: Map<String, Target>,
        registry: PoiRegistry = PoiRegistry(emptyList(), emptyList()),
        dataDir: File = File("/tmp"),
        etlRegistry: Map<String, TerminalEtlBinding<*, *>> = emptyMap(),
    ): IngestController =
        IngestController(
            ingestRunRepo = IngestRunRepo(ctx),
            adminReadRepo = AdminIngestReadRepo(ctx),
            etl =
                EtlOrchestrator(
                    importRunRepo = ImportRunRepo(ctx),
                    rawDir = dataDir,
                    poiRegistry = registry,
                    staticDir = dataDir,
                    etlRegistry = etlRegistry,
                ),
            importTargets = targets,
            ioDispatcher = Dispatchers.IO,
        )
```

In `backend/src/test/kotlin/ca/floo/roadtrip/route/AdminIngestRoutesTest.kt`, add the same two imports plus `import ca.floo.roadtrip.repo.IngestRunRepo`, and replace `controllerWith`:

```kotlin
    private fun controllerWith(
        targets: Map<String, Target>,
        etl: EtlOrchestrator =
            EtlOrchestrator(
                importRunRepo = ImportRunRepo(ctx),
                rawDir = File("/tmp"),
                poiRegistry = PoiRegistry(emptyList(), emptyList()),
                staticDir = File("/tmp"),
                etlRegistry = emptyMap(),
            ),
    ): IngestController =
        IngestController(
            ingestRunRepo = IngestRunRepo(ctx),
            adminReadRepo = AdminIngestReadRepo(ctx),
            etl = etl,
            importTargets = targets,
            ioDispatcher = Dispatchers.IO,
        )
```

and in `blockingController`, change the inner orchestrator's first argument and add its registry line — the `etlRegistry = mapOf(BUSY_ETL_SLUG to TerminalEtlBinding(...))` block it already passes is unchanged:

```kotlin
                EtlOrchestrator(
                    importRunRepo = ImportRunRepo(ctx),
                    rawDir = File("/tmp"),
```

- [ ] **Step 12: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. `grep -rn "org.jooq" backend/src/main/kotlin/ca/floo/roadtrip/service` now matches only `PoisOnRouteService.kt` and `RouteCorridorService.kt`, which Task 4 takes.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip backend/src/test/kotlin/ca/floo/roadtrip
git commit -m "$(cat <<'EOF'
refactor(etl): sinks bind to Repos and phase counts are a model, not JSONB

Refs #739

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: jOOQ exceptions stop at the repo, `RouteModule` stops building repos, and a guard test ratchets it shut

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/support/CorridorUnavailableException.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/repo/RouteCorridorRepoTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/LayeringGuardTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/RouteCorridorRepo.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/routing/RouteCorridorService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/PoisOnRouteService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/RouteCorridorRepoTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/repo/PoiServingRepoTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/LayeringGuardTest.kt`

**Interfaces:**
- Consumes: `UnitOfWork` (Task 1); `ca.floo.roadtrip.fixtures.repoRoot: File` (existing, in `backend/src/test/kotlin/ca/floo/roadtrip/fixtures/RepoFiles.kt`, found by walking up to the directory holding `secrets/registry.yaml`); `ca.floo.roadtrip.support.causeChain(e: Throwable): String`; the Koin singles registered in Task 1.
- Produces:
  - `class CorridorUnavailableException(cause: Throwable) : RuntimeException("route corridor query failed", cause)` in `ca.floo.roadtrip.support`
  - `internal fun isTopologyFault(e: DataAccessException): Boolean` in `ca.floo.roadtrip.repo` (declared in `PoiServingRepo.kt`)
  - `private fun availabilityDashboardController(pollerRepo, runRepo, availabilityRepo, campsiteRepo, forcePullCooldown)`, `private fun campsiteAvailabilityController(...)`, `private fun availabilityWatchController(...)`, and `private fun authRouteWiring(unitOfWork, userRepo, userSessionRepo, config)` in `RouteModule` — all `DSLContext`-free
  - `LayeringGuardTest` with three tests: the jOOQ allowlist, the `io.ktor`-under-`service/` allowlist, and no `ca.floo.roadtrip.repo` import under `route/`

- [ ] **Step 1: Write the failing `RouteCorridorRepoTest`**

Create `backend/src/test/kotlin/ca/floo/roadtrip/repo/RouteCorridorRepoTest.kt`. A GeoJSON string PostGIS cannot parse is a deterministic `DataAccessException` source, and the point of the task is that callers never see that type:

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.support.CorridorUnavailableException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val VANCOUVER_TO_SEATTLE =
    """{"type":"LineString","coordinates":[[-123.1207,49.2827],[-122.3321,47.6062]]}"""
private const val NOT_GEOJSON = "definitely not geojson"
private const val TEN_MILES_IN_METERS = 16_093.4

class RouteCorridorRepoTest : SharedDbTest() {
    private val repo by lazy { RouteCorridorRepo(ctx) }

    @Test
    fun `buffering a line returns a polygon`() {
        val polygon = repo.bufferedPolygonGeoJson(VANCOUVER_TO_SEATTLE, TEN_MILES_IN_METERS)

        assertTrue(polygon.contains("\"type\":\"Polygon\"") || polygon.contains("\"type\": \"Polygon\""))
    }

    @Test
    fun `a geometry PostGIS cannot read surfaces as a domain failure, never as jOOQ's`() {
        val failure =
            assertFailsWith<CorridorUnavailableException> {
                repo.bufferedPolygonGeoJson(NOT_GEOJSON, TEN_MILES_IN_METERS)
            }

        assertEquals("route corridor query failed", failure.message)
    }
}
```

- [ ] **Step 2: Add the topology-fault cases to `PoiServingRepoTest`**

Append to `backend/src/test/kotlin/ca/floo/roadtrip/repo/PoiServingRepoTest.kt`, inside the class, and add the imports `org.jooq.exception.DataAccessException`, `kotlin.test.assertFailsWith`, `kotlin.test.assertFalse`, `kotlin.test.assertTrue`:

```kotlin
    @Test
    fun `a GEOS topology fault reads as a bad corridor shape, not an outage`() {
        val fault = DataAccessException("SQL [...]", IllegalStateException("TopologyException: side location conflict"))

        assertTrue(isTopologyFault(fault))
    }

    @Test
    fun `any other data-access failure is not a topology fault`() {
        assertFalse(isTopologyFault(DataAccessException("connection reset by peer")))
    }

    @Test
    fun `a polygon PostGIS cannot parse still fails loudly rather than serving zero POIs`() {
        assertFailsWith<DataAccessException> { repo().fetchPoisWithinPolygon("not-geojson", listOf("campground")) }
    }
```

- [ ] **Step 3: Write the failing `LayeringGuardTest`**

Create `backend/src/test/kotlin/ca/floo/roadtrip/LayeringGuardTest.kt`. It reads the main source tree as text; the three rules are the ones in this plan's Global Constraints:

```kotlin
package ca.floo.roadtrip

import ca.floo.roadtrip.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

private const val MAIN_SOURCE_ROOT = "backend/src/main/kotlin"
private const val KOTLIN_EXTENSION = ".kt"

private const val JOOQ_IMPORT = "import org.jooq"
private const val KTOR_IMPORT = "import io.ktor"
private const val REPO_IMPORT = "import ca.floo.roadtrip.repo"

/** Persistence owns jOOQ; the two infrastructure DI modules are allowed to name it to wire it. */
private val JOOQ_ALLOWED =
    listOf(
        "ca/floo/roadtrip/repo/",
        "ca/floo/roadtrip/db/",
        "ca/floo/roadtrip/di/InfraModule.kt",
        "ca/floo/roadtrip/di/RepoModule.kt",
    )

/** Builds an OIDC redirect URL with URLBuilder; it serves no HTTP. */
private const val KTOR_ALLOWED_UNDER_SERVICE = "ca/floo/roadtrip/service/auth/OidcIdentityProvider.kt"

private const val SERVICE_PREFIX = "ca/floo/roadtrip/service/"
private const val ROUTE_PREFIX = "ca/floo/roadtrip/route/"

/**
 * Three seams that no compiler enforces. Each was a real drift: a service that
 * held a DSLContext, an adapter that imported HttpStatusCode to build a 503
 * nothing called, a route that reached past its controller into a repo.
 */
class LayeringGuardTest {
    private val sourceRoot = File(repoRoot, MAIN_SOURCE_ROOT)

    private val sources: List<Pair<String, String>> =
        sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(KOTLIN_EXTENSION) }
            .map { it.relativeTo(sourceRoot).invariantSeparatorsPath to it.readText() }
            .sortedBy { it.first }
            .toList()

    @Test
    fun `the source tree is actually being read`() {
        check(sources.size > 100) { "expected the whole backend main tree under $MAIN_SOURCE_ROOT, found ${sources.size} files" }
    }

    @Test
    fun `only repo, db and the infrastructure DI modules name jOOQ`() =
        assertEquals(
            emptyList(),
            sources
                .filter { (path, text) -> text.contains(JOOQ_IMPORT) && JOOQ_ALLOWED.none { path.startsWith(it) } }
                .map { it.first },
            "jOOQ belongs to persistence. Take a repo or a UnitOfWork instead of a DSLContext, " +
                "and translate org.jooq.exception.DataAccessException inside the repo.",
        )

    @Test
    fun `only the OIDC redirect builder names Ktor under service`() =
        assertEquals(
            emptyList(),
            sources
                .filter { (path, text) ->
                    path.startsWith(SERVICE_PREFIX) && text.contains(KTOR_IMPORT) && path != KTOR_ALLOWED_UNDER_SERVICE
                }.map { it.first },
            "services do not construct HTTP responses. Return a typed outcome and let the route map it to a status.",
        )

    @Test
    fun `routes never reach into a repo`() =
        assertEquals(
            emptyList(),
            sources.filter { (path, text) -> path.startsWith(ROUTE_PREFIX) && text.contains(REPO_IMPORT) }.map { it.first },
            "routes are the HTTP shell. Put the read behind a service or controller.",
        )
}
```

- [ ] **Step 4: Run the three test classes to verify they fail**

Run: `./gradlew :backend:test --tests '*RouteCorridorRepoTest*' --tests '*PoiServingRepoTest*' --tests '*LayeringGuardTest*' --offline -q`
Expected: FAIL — `Unresolved reference: CorridorUnavailableException`, `Unresolved reference: isTopologyFault`, and (once those compile) `only repo, db and the infrastructure DI modules name jOOQ` listing `ca/floo/roadtrip/di/RouteModule.kt`, `ca/floo/roadtrip/di/ServiceModule.kt` if Task 2 was skipped, `ca/floo/roadtrip/service/poi/PoisOnRouteService.kt`, and `ca/floo/roadtrip/service/routing/RouteCorridorService.kt`.

- [ ] **Step 5: Write the domain exception**

Create `backend/src/main/kotlin/ca/floo/roadtrip/support/CorridorUnavailableException.kt`:

```kotlin
package ca.floo.roadtrip.support

/** PostGIS could not buffer the route line. Callers see this, never jOOQ's DataAccessException. */
class CorridorUnavailableException(
    cause: Throwable,
) : RuntimeException("route corridor query failed", cause)
```

- [ ] **Step 6: Translate at the two repo boundaries**

Replace the whole of `backend/src/main/kotlin/ca/floo/roadtrip/repo/RouteCorridorRepo.kt`:

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.support.CorridorUnavailableException
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

internal class RouteCorridorRepo(
    private val ctx: DSLContext,
) {
    /** @throws CorridorUnavailableException when PostGIS cannot buffer the line. */
    fun bufferedPolygonGeoJson(
        lineGeoJson: String,
        radiusMeters: Double,
    ): String {
        val record =
            try {
                ctx.fetchOne(
                    """
                    SELECT ST_AsGeoJSON(
                             ST_CollectionExtract(
                               ST_MakeValid(
                                 ST_Buffer(
                                   ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography,
                                   ?
                                 )::geometry
                               ),
                               3
                             )
                           ) AS geom_json
                    """.trimIndent(),
                    lineGeoJson,
                    radiusMeters,
                )
            } catch (e: DataAccessException) {
                throw CorridorUnavailableException(e)
            }
        return record?.get("geom_json") as? String
            ?: error("route corridor query returned no geometry")
    }
}
```

In `backend/src/main/kotlin/ca/floo/roadtrip/repo/PoiServingRepo.kt`, add the imports

```kotlin
import ca.floo.roadtrip.support.causeChain
import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory
```

add these file-private declarations beside the other private constants at the top:

```kotlin
private const val TOPOLOGY_FAULT = "TopologyException"

private val poiServingLog = LoggerFactory.getLogger("PoiServingRepo")

/** A GEOS self-intersection in one corridor polygon is a bad shape, not an outage. */
internal fun isTopologyFault(e: DataAccessException): Boolean = causeChain(e).contains(TOPOLOGY_FAULT)
```

and wrap the `fetchPoisWithinPolygon` execution — the SQL and the `args` list above it are unchanged; only the final `return` becomes:

```kotlin
        return try {
            ctx.fetch(sql, *args.toTypedArray()).map { r ->
                PoiRow(
                    id = (r.get("id") as Number).toLong(),
                    category = r.get("category") as String,
                    subcategory = r.get("subcategory") as String?,
                    agency = r.get("agency") as String?,
                    lng = (r.get("lng") as Number).toDouble(),
                    lat = (r.get("lat") as Number).toDouble(),
                )
            }
        } catch (e: DataAccessException) {
            if (!isTopologyFault(e)) throw e
            poiServingLog.warn("on-route GEOS topology fault, returning empty: {}", causeChain(e))
            emptyList()
        }
```

- [ ] **Step 7: Take jOOQ out of the two services**

Replace the whole of `backend/src/main/kotlin/ca/floo/roadtrip/service/routing/RouteCorridorService.kt`. It becomes `open` here so Task 5's `RoutePlanServiceTest` can answer without PostGIS:

```kotlin
package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.support.CorridorUnavailableException
import ca.floo.roadtrip.support.RoutingException

private const val CORRIDOR_UNAVAILABLE = "corridor_unavailable"

internal open class RouteCorridorService(
    private val routeCorridorRepo: RouteCorridorRepo,
) {
    /** @throws RoutingException when PostGIS cannot buffer the line; callers see a domain failure. */
    open fun bufferedPolygonGeoJson(
        lineGeoJson: String,
        radiusMiles: Double,
    ): String =
        try {
            routeCorridorRepo.bufferedPolygonGeoJson(
                lineGeoJson = lineGeoJson,
                radiusMeters = routeCorridorRadiusMeters(radiusMiles),
            )
        } catch (e: CorridorUnavailableException) {
            throw RoutingException(CORRIDOR_UNAVAILABLE, e)
        }
}
```

In `backend/src/main/kotlin/ca/floo/roadtrip/service/poi/PoisOnRouteService.kt`, delete `import org.jooq.exception.DataAccessException` and the second catch arm. The repo now serves empty on its own topology fault; what is left here is the corridor-buffer fault, which arrives wrapped in `RoutingException`:

```kotlin
        return try {
            val polygonGeoJson =
                routeCorridorService.bufferedPolygonGeoJson(
                    lineGeoJson = lineGeoJson,
                    radiusMiles = radiusMiles,
                )
            poiService.poisWithinPolygon(
                polygonGeoJson = polygonGeoJson,
                categories = requestedCategories,
            )
        } catch (e: RoutingException) {
            emptyOnTopologyFault(e)
        }
```

and narrow the helper's parameter, since `RoutingException` is now its only caller:

```kotlin
    /** A GEOS self-intersection on one corridor is a bad shape, not an outage: serve zero POIs. */
    private fun emptyOnTopologyFault(e: RoutingException): List<PoiRow> {
        val chain = causeChain(e)
        if (!chain.contains(TOPOLOGY_FAULT)) throw e
        poisOnRouteLog.warn("on-route GEOS topology fault, returning empty: {}", chain)
        return emptyList()
    }
```

- [ ] **Step 8: Take the `DSLContext` out of `RouteModule`**

In `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, delete `import org.jooq.DSLContext`, add `import ca.floo.roadtrip.repo.RefLinkRepo`-free equivalents — the final import set for repos and the ref resolver is:

```kotlin
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.UnitOfWork
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSessionRepo
import ca.floo.roadtrip.service.ref.RefResolver
```

(delete `import ca.floo.roadtrip.repo.RefLinkRepo` and `import ca.floo.roadtrip.service.ref.DbRefResolver` — the `RefResolver` single already wraps it).

In `registerKoinRoutes`, replace `val ctx: DSLContext by inject()` with the repo injections, keeping `unitOfWork` from Task 2:

```kotlin
    val unitOfWork: UnitOfWork by inject()
    val poiRepo: PoiRepo by inject()
    val campsiteRepo: CampsiteRepo by inject()
    val campgroundRepo: CampgroundRepo by inject()
    val availabilityRepo: AvailabilityRepo by inject()
    val availabilityRunRepo: AvailabilityRunRepo by inject()
    val pollerRepo: AvailabilityPollerRepo by inject()
    val watchRepo: AvailabilityWatchRepo by inject()
    val userRepo: UserRepo by inject()
    val userSessionRepo: UserSessionRepo by inject()
    val refResolver: RefResolver by inject()
```

and update the five call sites inside it:

```kotlin
    val authWiring = authRouteWiring(unitOfWork, userRepo, userSessionRepo, config)
```

```kotlin
        availabilityWatchRoutes(
            availabilityWatchController(campsiteRepo, watchRepo, userRepo, watchService, watchCapabilities),
        )
        val campsiteController =
            campsiteAvailabilityController(
                poiRepo = poiRepo,
                campsitesRepo = campsiteRepo,
                campgroundRepo = campgroundRepo,
                availabilityRepo = availabilityRepo,
                pollerRepo = pollerRepo,
                refResolver = refResolver,
                availabilityProviders = availabilityProviders,
                dateResolver = dateResolver,
                bookingHorizons = bookingHorizons,
                failoverFetcher = failoverFetcher,
                watchCapabilities = watchCapabilities,
                cacheConfig = config.cache,
                identities = bookingIdentities,
            )
```

```kotlin
        availabilityDashboardRoutes(
            availabilityDashboardController(
                pollerRepo = pollerRepo,
                runRepo = availabilityRunRepo,
                availabilityRepo = availabilityRepo,
                campsiteRepo = campsiteRepo,
                forcePullCooldown = config.availability.forcePullCooldown,
            ),
        )
```

Then replace the four private helpers. `availabilityDashboardController` becomes a pass-through:

```kotlin
private fun availabilityDashboardController(
    pollerRepo: AvailabilityPollerRepo,
    runRepo: AvailabilityRunRepo,
    availabilityRepo: AvailabilityRepo,
    campsiteRepo: CampsiteRepo,
    forcePullCooldown: Duration,
): AvailabilityDashboardController =
    AvailabilityDashboardController(
        pollerRepo = pollerRepo,
        runRepo = runRepo,
        availabilityRepo = availabilityRepo,
        campsiteRepo = campsiteRepo,
        forcePullCooldown = forcePullCooldown,
    )
```

`campsiteAvailabilityController` takes the six repos it used to build (its KDoc's "the DSLContext stays here in composition code" sentence is now false — delete that sentence and keep the rest):

```kotlin
/**
 * Assembles the campsite read-slice controller. Mirrors [availabilityWatchController]:
 * composition stays here, so the route file remains a pure HTTP shell.
 */
@Suppress("LongParameterList")
private fun campsiteAvailabilityController(
    poiRepo: PoiRepo,
    campsitesRepo: CampsiteRepo,
    campgroundRepo: CampgroundRepo,
    availabilityRepo: AvailabilityRepo,
    pollerRepo: AvailabilityPollerRepo,
    refResolver: RefResolver,
    availabilityProviders: List<AvailabilityProvider>,
    dateResolver: AvailabilityDateResolver,
    bookingHorizons: BookingHorizonResolver,
    failoverFetcher: FailoverAvailabilityFetcher,
    watchCapabilities: WatchCapabilityService,
    cacheConfig: ApiCacheConfig,
    identities: BookingIdentityResolver,
): CampsiteAvailabilityController {
    val targets =
        DbAvailabilityTargetResolver(
            poiRepo = poiRepo,
            campsitesRepo = campsitesRepo,
            campgroundRepo = campgroundRepo,
            availabilityProviders = availabilityProviders,
            dateResolver = dateResolver,
            pollerRepo = pollerRepo,
        )
    return CampsiteAvailabilityController(
        campgroundRepo = campgroundRepo,
        campsitesRepo = campsitesRepo,
        catalogService =
            CampsiteCatalogService(
                refResolver = refResolver,
                campsitesRepo = campsitesRepo,
                campgroundRepo = campgroundRepo,
                targets = targets,
                identities = identities,
            ),
        availabilityService =
            CampsiteAvailabilityService(
                availabilityProviders = availabilityProviders,
                dateResolver = dateResolver,
                failoverFetcher = failoverFetcher,
                bookingHorizons = bookingHorizons,
                availabilityRepo = availabilityRepo,
                snapshotFreshnessTtl = configuredSnapshotFreshnessTtl(cacheConfig),
            ),
        dateResolver = dateResolver,
        watchCapabilityService = watchCapabilities,
    )
}
```

`availabilityWatchController`:

```kotlin
private fun availabilityWatchController(
    campsitesRepo: CampsiteRepo,
    watchRepo: AvailabilityWatchRepo,
    userRepo: UserRepo,
    watchService: AvailabilityWatchService,
    watchCapabilities: WatchCapabilityService,
): AvailabilityWatchController =
    AvailabilityWatchController(
        watchRepo = watchRepo,
        watchService = watchService,
        watchMapper =
            AvailabilityWatchApiMapper(
                campsiteRepo = campsitesRepo,
                scopeResolver = WatchScopeResolver(campsitesRepo),
                watchCapabilityService = watchCapabilities,
            ),
        accessResolver = WatchAccessResolver(watchRepo = watchRepo, userRepo = userRepo),
    )
```

`authRouteWiring` keeps its whole KDoc; only its signature and the two repo lines change:

```kotlin
private fun authRouteWiring(
    unitOfWork: UnitOfWork,
    userRepo: UserRepo,
    userSessionRepo: UserSessionRepo,
    config: AppConfig,
): AuthRouteWiring? {
```

```kotlin
    val sessionService =
        SessionService(
            userRepo = userRepo,
            userSessionRepo = userSessionRepo,
            sessionTtl = authConfig.sessionTtl,
        )
```

(the `val userRepo = UserRepo(ctx)` line above it is deleted; `UserProvisioningService(unitOfWork, authConfig.roleGrants)` from Task 2 is unchanged.)

- [ ] **Step 9: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS, including all four `LayeringGuardTest` cases. If `LongParameterList` was already suppressed project-wide in `detekt.yml`, drop the `@Suppress` added above rather than leaving a redundant one — detekt reports redundant suppressions.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip backend/src/test/kotlin/ca/floo/roadtrip
git commit -m "$(cat <<'EOF'
refactor(repo): translate jOOQ failures at the repo, and guard the seams

Refs #739

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `/api/route` becomes an HTTP shell over a route-plan service

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/routing/RoutePlan.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/routing/RoutePlanService.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/api/RouteResponseMapper.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/routing/RoutePlanServiceTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/api/RouteResponseMapperTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/route/RouteRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`
- Delete: `backend/src/test/kotlin/ca/floo/roadtrip/route/RouteRoutesTest.kt` (its two shape assertions are subsumed by the byte-for-byte golden in `RouteResponseMapperTest`, and the function it called is gone)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/RouteRoutesTest.kt` (Step 1 only, as the characterization pin), then `backend/src/test/kotlin/ca/floo/roadtrip/service/api/RouteResponseMapperTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/routing/RoutePlanServiceTest.kt`

**Interfaces:**
- Consumes: `RouteCache.configured: Boolean`, `RouteCache.directions(waypoints: List<Pair<Double, Double>>): RouteResponse` (suspend), `RouteCache.put(waypoints, response)`; `RouteCorridorService.bufferedPolygonGeoJson(lineGeoJson: String, radiusMiles: Double): String`, now `open` (Task 4); `lineStringGeoJson(coords: List<List<Double>>): String` in `service/routing/RouteGeometry.kt`; `RouteConfig(maxWaypoints, minCorridorRadiusMiles, maxCorridorRadiusMiles)`.
- Produces:
  - `data class RoutePlan(val directions: RouteResponse, val waypoints: List<Pair<Double, Double>>, val corridorRadiusMiles: Double?, val corridorGeoJson: JsonElement?)` in `ca.floo.roadtrip.model.routing`
  - `internal sealed interface RoutePlanResult` with `data class Planned(val plan: RoutePlan)`, `data class DuplicateWaypoints(val index: Int)`, `data class DirectionsUnavailable(val detail: String)`, `data class CorridorUnavailable(val detail: String)`
  - `internal class RoutePlanService(routeCache: RouteCache, corridorService: RouteCorridorService)` with `val configured: Boolean` and `suspend fun plan(waypoints: List<Pair<Double, Double>>, corridorRadiusMiles: Double?): RoutePlanResult`
  - `internal class RouteResponseMapper` with `fun featureCollection(plan: RoutePlan): RouteFeatureCollectionDto`
  - `internal fun Route.routeRoutes(routePlanService: RoutePlanService, routeResponseMapper: RouteResponseMapper, routeConfig: RouteConfig)`
  - `routeResponseFeatureCollection` no longer exists

- [ ] **Step 1: Pin today's bytes before touching anything**

This is the wire guard the Global Constraints require. Append to the existing `backend/src/test/kotlin/ca/floo/roadtrip/route/RouteRoutesTest.kt`, inside the class, and add `import ca.floo.roadtrip.route.common.encodeApiJson` if it is not already there (it is) plus `kotlin.test.assertEquals` (already there):

```kotlin
    @Test
    fun `the composite feature collection is exactly these bytes`() {
        assertEquals(
            ROUTE_AND_CORRIDOR_JSON,
            encodeApiJson(
                routeResponseFeatureCollection(
                    response =
                        RouteResponse(
                            coordinates = listOf(listOf(-123.1, 49.28), listOf(-122.33, 47.61)),
                            distanceMeters = 1000.0,
                            durationSeconds = 90.0,
                            legs = listOf(RouteLeg(distanceMeters = 1000.0, durationSeconds = 90.0)),
                        ),
                    waypoints = listOf(-123.1 to 49.28, -122.33 to 47.61),
                    corridorRadiusMiles = 5.0,
                    corridorPolygonGeoJson = CORRIDOR_POLYGON_JSON,
                ),
            ),
        )
    }
```

and above the class, at file scope:

```kotlin
/** The corridor polygon as PostGIS hands it over: already-encoded GeoJSON text. */
private const val CORRIDOR_POLYGON_JSON =
    """{"type":"Polygon","coordinates":[[[-1.0,1.0],[1.0,1.0],[1.0,-1.0],[-1.0,1.0]]]}"""

/**
 * `/api/route`'s response, byte for byte. The frontend's map layers read every
 * key here; this refactor is not allowed to move one.
 */
private const val ROUTE_AND_CORRIDOR_JSON =
    """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString",""" +
        """"coordinates":[[-123.1,49.28],[-122.33,47.61]]},"properties":{"distance_m":1000.0,""" +
        """"duration_s":90.0,"legs":[{"distance_m":1000.0,"duration_s":90.0}],""" +
        """"waypoints":[[-123.1,49.28],[-122.33,47.61]]}},{"type":"Feature","geometry":{"type":"Polygon",""" +
        """"coordinates":[[[-1.0,1.0],[1.0,1.0],[1.0,-1.0],[-1.0,1.0]]]},"properties":{"role":"corridor",""" +
        """"radius_miles":5.0}}]}"""
```

- [ ] **Step 2: Run it to verify it passes against today's code**

Run: `./gradlew :backend:test --tests '*RouteRoutesTest*' --offline -q`
Expected: PASS. If it fails, the expected string in Step 1 is wrong, not the production code — fix the string from the actual output and carry the corrected value through the rest of this task. **Do not proceed until this is green:** the whole point is that the same string passes again at Step 8.

- [ ] **Step 3: Write the failing `RouteResponseMapperTest`**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/api/RouteResponseMapperTest.kt`. Same golden string, now asserted against the new mapper:

```kotlin
package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.routing.RouteLeg
import ca.floo.roadtrip.model.routing.RoutePlan
import ca.floo.roadtrip.model.routing.RouteResponse
import ca.floo.roadtrip.route.common.encodeApiJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The corridor polygon as PostGIS hands it over: already-encoded GeoJSON text. */
private const val CORRIDOR_POLYGON_JSON =
    """{"type":"Polygon","coordinates":[[[-1.0,1.0],[1.0,1.0],[1.0,-1.0],[-1.0,1.0]]]}"""

/**
 * `/api/route`'s response, byte for byte. The frontend's map layers read every
 * key here; nothing is allowed to move one.
 */
private const val ROUTE_AND_CORRIDOR_JSON =
    """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString",""" +
        """"coordinates":[[-123.1,49.28],[-122.33,47.61]]},"properties":{"distance_m":1000.0,""" +
        """"duration_s":90.0,"legs":[{"distance_m":1000.0,"duration_s":90.0}],""" +
        """"waypoints":[[-123.1,49.28],[-122.33,47.61]]}},{"type":"Feature","geometry":{"type":"Polygon",""" +
        """"coordinates":[[[-1.0,1.0],[1.0,1.0],[1.0,-1.0],[-1.0,1.0]]]},"properties":{"role":"corridor",""" +
        """"radius_miles":5.0}}]}"""

private val directions =
    RouteResponse(
        coordinates = listOf(listOf(-123.1, 49.28), listOf(-122.33, 47.61)),
        distanceMeters = 1000.0,
        durationSeconds = 90.0,
        legs = listOf(RouteLeg(distanceMeters = 1000.0, durationSeconds = 90.0)),
    )

private val waypoints = listOf(-123.1 to 49.28, -122.33 to 47.61)

class RouteResponseMapperTest {
    private val mapper = RouteResponseMapper()

    @Test
    fun `a planned route with a corridor is exactly these bytes`() {
        val plan =
            RoutePlan(
                directions = directions,
                waypoints = waypoints,
                corridorRadiusMiles = 5.0,
                corridorGeoJson = Json.parseToJsonElement(CORRIDOR_POLYGON_JSON),
            )

        assertEquals(ROUTE_AND_CORRIDOR_JSON, encodeApiJson(mapper.featureCollection(plan)))
    }

    @Test
    fun `a route with no corridor carries one feature`() {
        val plan =
            RoutePlan(
                directions = directions,
                waypoints = waypoints,
                corridorRadiusMiles = null,
                corridorGeoJson = null,
            )

        val features =
            Json
                .parseToJsonElement(encodeApiJson(mapper.featureCollection(plan)))
                .jsonObject["features"]!!
                .jsonArray

        assertEquals(1, features.size)
        assertEquals("LineString", features.single().jsonObject["geometry"]!!.jsonObject["type"]!!.toString().trim('"'))
    }

    @Test
    fun `a radius with no polygon emits no corridor feature`() {
        // Defensive: the service never produces this pair, and the mapper must
        // not half-emit a corridor if it ever does.
        val plan =
            RoutePlan(
                directions = directions,
                waypoints = waypoints,
                corridorRadiusMiles = 5.0,
                corridorGeoJson = null,
            )

        assertEquals(
            1,
            Json
                .parseToJsonElement(encodeApiJson(mapper.featureCollection(plan)))
                .jsonObject["features"]!!
                .jsonArray.size,
        )
    }
}
```

- [ ] **Step 4: Write the failing `RoutePlanServiceTest`**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/routing/RoutePlanServiceTest.kt`. `MapboxDirections(token = null)` never reaches the network because the cache is seeded with `put` first — the same trick `RouteCacheTest` already uses. The corridor is a subclass over a detached context, the fake shape this repo already uses for repos:

```kotlin
package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.client.mapbox.MapboxDirections
import ca.floo.roadtrip.model.routing.RouteLeg
import ca.floo.roadtrip.model.routing.RouteResponse
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.support.RoutingException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val CORRIDOR_POLYGON_JSON = """{"type":"Polygon","coordinates":[[[-1.0,1.0],[1.0,1.0],[1.0,-1.0],[-1.0,1.0]]]}"""
private const val CORRIDOR_UNAVAILABLE = "corridor_unavailable"
private const val RADIUS_MILES = 5.0
private val CACHE_TTL: Duration = Duration.ofMinutes(10)

private val vancouver = -123.1207 to 49.2827
private val calgary = -114.0719 to 51.0447

private val cachedRoute =
    RouteResponse(
        coordinates = listOf(listOf(-123.1207, 49.2827), listOf(-114.0719, 51.0447)),
        distanceMeters = 971_000.0,
        durationSeconds = 37_000.0,
        legs = listOf(RouteLeg(distanceMeters = 971_000.0, durationSeconds = 37_000.0)),
    )

class RoutePlanServiceTest {
    @Test
    fun `adjacent duplicate waypoints refuse before any upstream call`() {
        // The cache is empty, so a service that reached Mapbox would throw
        // RoutingException("roadtrip.mapbox.token not configured") instead.
        val result = runBlocking { service(corridor = refusingCorridor()).plan(listOf(vancouver, vancouver), null) }

        assertEquals(RoutePlanResult.DuplicateWaypoints(index = 1), result)
    }

    @Test
    fun `a route with no corridor requested carries no polygon`() {
        val result = runBlocking { service(corridor = refusingCorridor()).plan(listOf(vancouver, calgary), null) }

        val planned = assertIs<RoutePlanResult.Planned>(result)
        assertEquals(cachedRoute, planned.plan.directions)
        assertEquals(listOf(vancouver, calgary), planned.plan.waypoints)
        assertNull(planned.plan.corridorRadiusMiles)
        assertNull(planned.plan.corridorGeoJson)
    }

    @Test
    fun `a requested corridor is carried parsed, not as text`() {
        val result = runBlocking { service(corridor = polygonCorridor()).plan(listOf(vancouver, calgary), RADIUS_MILES) }

        val planned = assertIs<RoutePlanResult.Planned>(result)
        assertEquals(RADIUS_MILES, planned.plan.corridorRadiusMiles)
        assertEquals("Polygon", planned.plan.corridorGeoJson!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a corridor failure is its own outcome, not a routing failure`() {
        val result = runBlocking { service(corridor = refusingCorridor()).plan(listOf(vancouver, calgary), RADIUS_MILES) }

        assertEquals(RoutePlanResult.CorridorUnavailable(CORRIDOR_UNAVAILABLE), result)
    }

    @Test
    fun `an unreachable directions upstream is its own outcome`() {
        val emptyCache = RouteCache(directions = MapboxDirections(token = null), ttl = CACHE_TTL)

        val result = runBlocking { RoutePlanService(emptyCache, refusingCorridor()).plan(listOf(vancouver, calgary), null) }

        assertEquals(RoutePlanResult.DirectionsUnavailable("roadtrip.mapbox.token not configured"), result)
    }

    private fun service(corridor: RouteCorridorService): RoutePlanService {
        val cache = RouteCache(directions = MapboxDirections(token = null), ttl = CACHE_TTL)
        cache.put(listOf(vancouver, calgary), cachedRoute)
        return RoutePlanService(cache, corridor)
    }

    /** A corridor service that answers without PostGIS. */
    private fun polygonCorridor(): RouteCorridorService =
        object : RouteCorridorService(RouteCorridorRepo(DSL.using(SQLDialect.POSTGRES))) {
            override fun bufferedPolygonGeoJson(
                lineGeoJson: String,
                radiusMiles: Double,
            ): String = CORRIDOR_POLYGON_JSON
        }

    private fun refusingCorridor(): RouteCorridorService =
        object : RouteCorridorService(RouteCorridorRepo(DSL.using(SQLDialect.POSTGRES))) {
            override fun bufferedPolygonGeoJson(
                lineGeoJson: String,
                radiusMiles: Double,
            ): String = throw RoutingException(CORRIDOR_UNAVAILABLE)
        }
}
```

- [ ] **Step 5: Run both new tests to verify they fail**

Run: `./gradlew :backend:test --tests '*RouteResponseMapperTest*' --tests '*RoutePlanServiceTest*' --offline -q`
Expected: FAIL to compile — `Unresolved reference: RoutePlan`, `RoutePlanService`, `RoutePlanResult`, `RouteResponseMapper`.

- [ ] **Step 6: Write the plan model, the service, and the mapper**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/routing/RoutePlan.kt`:

```kotlin
package ca.floo.roadtrip.model.routing

import kotlinx.serialization.json.JsonElement

/**
 * One planned route: the directions it resolved to, the waypoints it was asked
 * for, and the corridor polygon when a radius was requested. The polygon is
 * already parsed — the mapper embeds it rather than re-parsing text.
 */
data class RoutePlan(
    val directions: RouteResponse,
    val waypoints: List<Pair<Double, Double>>,
    val corridorRadiusMiles: Double?,
    val corridorGeoJson: JsonElement?,
)
```

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/routing/RoutePlanService.kt`:

```kotlin
package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.model.routing.RoutePlan
import ca.floo.roadtrip.support.RoutingException
import kotlinx.serialization.json.Json

/** What planning a route came to. The route maps each arm to a status. */
internal sealed interface RoutePlanResult {
    data class Planned(
        val plan: RoutePlan,
    ) : RoutePlanResult

    /** Mapbox rejects identical adjacent waypoints with code:"InvalidInput". */
    data class DuplicateWaypoints(
        val index: Int,
    ) : RoutePlanResult

    data class DirectionsUnavailable(
        val detail: String,
    ) : RoutePlanResult

    data class CorridorUnavailable(
        val detail: String,
    ) : RoutePlanResult
}

/**
 * The `/api/route` use case: directions, then optionally the server-side
 * corridor polygon around them. Owns the vendor rule about adjacent duplicates
 * so the route never has to know why Mapbox would refuse.
 */
internal class RoutePlanService(
    private val routeCache: RouteCache,
    private val corridorService: RouteCorridorService,
) {
    val configured: Boolean get() = routeCache.configured

    suspend fun plan(
        waypoints: List<Pair<Double, Double>>,
        corridorRadiusMiles: Double?,
    ): RoutePlanResult {
        duplicateAdjacentIndex(waypoints)?.let { return RoutePlanResult.DuplicateWaypoints(it) }

        val directions =
            try {
                routeCache.directions(waypoints)
            } catch (e: RoutingException) {
                return RoutePlanResult.DirectionsUnavailable(e.message.orEmpty())
            }

        val corridor =
            corridorRadiusMiles?.let { radiusMiles ->
                try {
                    Json.parseToJsonElement(
                        corridorService.bufferedPolygonGeoJson(
                            lineGeoJson = lineStringGeoJson(directions.coordinates),
                            radiusMiles = radiusMiles,
                        ),
                    )
                } catch (e: RoutingException) {
                    return RoutePlanResult.CorridorUnavailable(e.message.orEmpty())
                }
            }

        return RoutePlanResult.Planned(
            RoutePlan(
                directions = directions,
                waypoints = waypoints,
                corridorRadiusMiles = corridorRadiusMiles,
                corridorGeoJson = corridor,
            ),
        )
    }

    private fun duplicateAdjacentIndex(waypoints: List<Pair<Double, Double>>): Int? =
        (1 until waypoints.size).firstOrNull { waypoints[it] == waypoints[it - 1] }
}
```

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/api/RouteResponseMapper.kt`:

```kotlin
package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.api.CorridorFeatureDto
import ca.floo.roadtrip.model.api.CorridorPropertiesDto
import ca.floo.roadtrip.model.api.RouteFeatureCollectionDto
import ca.floo.roadtrip.model.api.RouteFeatureDto
import ca.floo.roadtrip.model.api.RouteLegDto
import ca.floo.roadtrip.model.api.RouteLineGeometryDto
import ca.floo.roadtrip.model.api.RoutePropertiesDto
import ca.floo.roadtrip.model.routing.RoutePlan
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

// Only for embedding each feature as a JsonElement inside the collection DTO;
// HTTP serialization belongs to the route layer. Same two settings as the API
// encoder, so the bytes are identical either way.
@OptIn(ExperimentalSerializationApi::class)
private val routeFeatureJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

internal class RouteResponseMapper {
    fun featureCollection(plan: RoutePlan): RouteFeatureCollectionDto {
        val features =
            mutableListOf(
                routeFeatureJson.encodeToJsonElement(
                    RouteFeatureDto(
                        geometry = RouteLineGeometryDto(coordinates = plan.directions.coordinates),
                        properties =
                            RoutePropertiesDto(
                                distanceMeters = plan.directions.distanceMeters,
                                durationSeconds = plan.directions.durationSeconds,
                                legs =
                                    plan.directions.legs.map { leg ->
                                        RouteLegDto(
                                            distanceMeters = leg.distanceMeters,
                                            durationSeconds = leg.durationSeconds,
                                        )
                                    },
                                waypoints = plan.waypoints.map { (lng, lat) -> listOf(lng, lat) },
                            ),
                    ),
                ),
            )
        val radiusMiles = plan.corridorRadiusMiles
        val corridorGeometry = plan.corridorGeoJson
        if (radiusMiles != null && corridorGeometry != null) {
            features +=
                routeFeatureJson.encodeToJsonElement(
                    CorridorFeatureDto(
                        geometry = corridorGeometry,
                        properties = CorridorPropertiesDto(radiusMiles = radiusMiles),
                    ),
                )
        }
        return RouteFeatureCollectionDto(features = features)
    }
}
```

- [ ] **Step 7: Make `RouteRoutes` an HTTP shell**

Replace the whole of `backend/src/main/kotlin/ca/floo/roadtrip/route/api/route/RouteRoutes.kt`. The coordinate parsing and the `radius_miles` range check stay — those are request parsing against config — and the error codes stay inline, as the spec says. Everything from the duplicate rule onward is gone:

```kotlin
package ca.floo.roadtrip.route.api.route

import ca.floo.roadtrip.config.RouteConfig
import ca.floo.roadtrip.model.api.RouteErrorDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.OptionalQuery
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.optionalDoubleQuery
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.trimmedQuery
import ca.floo.roadtrip.service.api.RouteResponseMapper
import ca.floo.roadtrip.service.routing.RoutePlanResult
import ca.floo.roadtrip.service.routing.RoutePlanService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * GET /api/route?coords=lng,lat;lng,lat;...
 *
 * Backend proxy for Mapbox Directions API. Token stays server-side.
 *
 * Returns:
 *   200 { type:"FeatureCollection", features: [ LineString feature with
 *         distance_m, duration_s, legs[] in properties ] }
 *   400 for malformed coords / wrong number of waypoints
 *   503 when roadtrip.mapbox.token is unset or upstream fails
 */
internal fun Route.routeRoutes(
    routePlanService: RoutePlanService,
    routeResponseMapper: RouteResponseMapper,
    routeConfig: RouteConfig,
) {
    route("/api") {
        get("/route") {
            if (!routePlanService.configured) {
                call.respondRouteError(
                    error = "routing_unavailable",
                    detail = "roadtrip.mapbox.token not set",
                    status = HttpStatusCode.ServiceUnavailable,
                )
                return@get
            }

            val raw = call.trimmedQuery("coords")
            val pieces = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }

            if (pieces.size < 2) {
                call.respondRouteError(
                    error = "too_few_points",
                    detail = "need >= 2 waypoints in coords=lng,lat;lng,lat[;...]",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }
            if (pieces.size > routeConfig.maxWaypoints) {
                call.respondRouteError(
                    error = "too_many_points",
                    detail = "max ${routeConfig.maxWaypoints} waypoints",
                    status = HttpStatusCode.BadRequest,
                )
                return@get
            }

            val coords = mutableListOf<Pair<Double, Double>>()
            for ((i, p) in pieces.withIndex()) {
                val parts = p.split(",")
                if (parts.size != 2) {
                    call.respondRouteError(
                        error = "bad_coords",
                        detail = "point $i: '$p' is not 'lng,lat'",
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }
                val lng = parts[0].toDoubleOrNull()
                val lat = parts[1].toDoubleOrNull()
                if (lng == null || lat == null) {
                    call.respondRouteError(
                        error = "bad_coords",
                        detail = "point $i: '$p' is not 'lng,lat'",
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }
                if (lng !in -180.0..180.0 || lat !in -90.0..90.0) {
                    call.respondRouteError(
                        error = "out_of_range",
                        detail = "point $i out of lng/lat range",
                        status = HttpStatusCode.BadRequest,
                    )
                    return@get
                }
                coords.add(lng to lat)
            }
            val corridorRadiusMiles =
                when (val radiusQuery = call.optionalDoubleQuery("radius_miles")) {
                    OptionalQuery.Missing -> null
                    is OptionalQuery.Invalid ->
                        return@get call.respondRouteError(
                            error = "bad_radius",
                            detail = "radius_miles must be a number",
                            status = HttpStatusCode.BadRequest,
                        )
                    is OptionalQuery.Parsed -> {
                        val radius = radiusQuery.value
                        if (radius !in routeConfig.minCorridorRadiusMiles..routeConfig.maxCorridorRadiusMiles) {
                            return@get call.respondRouteError(
                                error = "bad_radius",
                                detail =
                                    "radius_miles must be in " +
                                        "[${routeConfig.minCorridorRadiusMiles}, ${routeConfig.maxCorridorRadiusMiles}]",
                                status = HttpStatusCode.BadRequest,
                            )
                        }
                        radius
                    }
                }

            when (val result = routePlanService.plan(coords, corridorRadiusMiles)) {
                is RoutePlanResult.DuplicateWaypoints ->
                    call.respondRouteError(
                        error = "duplicate_adjacent",
                        detail = "points ${result.index} and ${result.index - 1} are identical",
                        status = HttpStatusCode.BadRequest,
                    )
                is RoutePlanResult.DirectionsUnavailable ->
                    call.respondRouteError(
                        error = "routing_unavailable",
                        detail = result.detail,
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                is RoutePlanResult.CorridorUnavailable ->
                    call.respondRouteError(
                        error = "corridor_unavailable",
                        detail = result.detail,
                        status = HttpStatusCode.ServiceUnavailable,
                    )
                is RoutePlanResult.Planned ->
                    call.respondEncodedJson(routeResponseMapper.featureCollection(result.plan))
            }
        }.access(RouteAccess.Anonymous)
    }
}

private suspend fun ApplicationCall.respondRouteError(
    error: String,
    detail: String,
    status: HttpStatusCode,
) {
    respondEncodedJson(RouteErrorDto(error = error, detail = detail), status)
}
```

- [ ] **Step 8: Delete the characterization pin's home and rewire DI**

```bash
git rm backend/src/test/kotlin/ca/floo/roadtrip/route/RouteRoutesTest.kt
```

The identical golden string now lives in `RouteResponseMapperTest`; nothing is lost.

In `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, add the imports `ca.floo.roadtrip.service.api.RouteResponseMapper` and `ca.floo.roadtrip.service.routing.RoutePlanService`, and add beside the existing `RouteCorridorService` single:

```kotlin
        single { RoutePlanService(routeCache = get(), corridorService = get<RouteCorridorService>()) }
        single { RouteResponseMapper() }
```

In `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, delete `import ca.floo.roadtrip.service.routing.RouteCache` and `import ca.floo.roadtrip.service.routing.RouteCorridorService`, add `import ca.floo.roadtrip.service.api.RouteResponseMapper` and `import ca.floo.roadtrip.service.routing.RoutePlanService`, replace the two injections

```kotlin
    val routeCache: RouteCache by inject()
    val routeCorridorService: RouteCorridorService by inject()
```

with

```kotlin
    val routePlanService: RoutePlanService by inject()
    val routeResponseMapper: RouteResponseMapper by inject()
```

and change the mount:

```kotlin
        routeRoutes(routePlanService, routeResponseMapper, config.route)
```

(`poisOnRouteService` is still injected whole and keeps its own `RouteCache` and `RouteCorridorService` internally.)

- [ ] **Step 9: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS, including `RouteResponseMapperTest.a planned route with a corridor is exactly these bytes` — the same string that was green at Step 2.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip backend/src/test/kotlin/ca/floo/roadtrip
git commit -m "$(cat <<'EOF'
refactor(route): a route-plan service and response mapper behind /api/route

Refs #742

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `/api/geocode` gets the service it never had

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/geocode/GeocodeService.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/geocode/GeocodeServiceTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/geocode/GeocodeRoutes.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/GeocodeRoutesTest.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/geocode/GeocodeServiceTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/GeocodeRoutesTest.kt`

**Interfaces:**
- Consumes: `MapboxGeocoder.configured: Boolean` and `suspend fun forward(q: String, autocomplete: Boolean = true, proximity: String? = null, limit: Int = 5): List<GeocodeResult>`, which throws `ca.floo.roadtrip.support.GeocodeException`; `GeocodeResponseDto(results: List<GeocodeResultDto>)`; `GeocodeResultDto(id, placeName, placeType, lng, lat, bbox)`; `internal fun Application.routeTestApplication(body: Route.() -> Unit)` in `backend/src/test/kotlin/ca/floo/roadtrip/route/RouteTestApplication.kt`.
- Produces:
  - `internal sealed interface GeocodeOutcome` with `data class Found(val response: GeocodeResponseDto)`, `data object NotConfigured`, `data object BadQuery`, `data object Unavailable`
  - `internal class GeocodeService(private val geocoder: MapboxGeocoder)` with `suspend fun geocode(query: String, autocomplete: Boolean, proximity: String?, limit: Int?): GeocodeOutcome`
  - `internal fun geocodeResponseDto(results: List<GeocodeResult>): GeocodeResponseDto` — moved verbatim from `route/api/geocode/GeocodeRoutes.kt` to `service/geocode/GeocodeService.kt`
  - `fun Route.geocodeRoutes(geocodeService: GeocodeService)`

- [ ] **Step 1: Write the failing `GeocodeServiceTest`**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/geocode/GeocodeServiceTest.kt`. The two DTO-shape cases are the ones moving out of `GeocodeRoutesTest`; the rest are the behaviours that used to live in the route and had no test at all. A request-capturing `MockEngine` is how `MapboxGeocoderTest` already asserts what went upstream:

```kotlin
package ca.floo.roadtrip.service.geocode

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.route.common.encodeApiJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OVER_MAX_QUERY_LENGTH = 201
private const val VANCOUVER_FEATURE =
    """{"features":[{"id":"place.1","place_name":"Vancouver, British Columbia, Canada",""" +
        """"place_type":["place"],"center":[-123.1207,49.2827]}]}"""
private const val UTAH_FEATURE =
    """{"features":[{"id":"region.1","place_name":"Utah, United States","place_type":["region"],""" +
        """"center":[-111.0937,39.3210],"bbox":[-114.052,36.997,-109.041,42.001]}]}"""

class GeocodeServiceTest {
    private val requests = mutableListOf<HttpRequestData>()

    @Test
    fun `an unset token refuses before any upstream call`() {
        val outcome = runBlocking { serviceWithoutToken().geocode("Vancouver", autocomplete = true, proximity = null, limit = null) }

        assertEquals(GeocodeOutcome.NotConfigured, outcome)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a blank query is refused`() {
        val outcome = runBlocking { service(VANCOUVER_FEATURE).geocode("   ", autocomplete = true, proximity = null, limit = null) }

        assertEquals(GeocodeOutcome.BadQuery, outcome)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a query over the length cap is refused`() {
        val outcome =
            runBlocking {
                service(VANCOUVER_FEATURE).geocode("x".repeat(OVER_MAX_QUERY_LENGTH), autocomplete = true, proximity = null, limit = null)
            }

        assertEquals(GeocodeOutcome.BadQuery, outcome)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `limit is clamped into range, and an absent one takes the default`() {
        val geocoder = service(VANCOUVER_FEATURE)

        runBlocking { geocoder.geocode("Vancouver", autocomplete = true, proximity = null, limit = 99) }
        runBlocking { geocoder.geocode("Vancouver", autocomplete = true, proximity = null, limit = 0) }
        runBlocking { geocoder.geocode("Vancouver", autocomplete = true, proximity = null, limit = null) }

        assertEquals(listOf("10", "1", "5"), requests.map { queryParamOf(it, "limit") })
    }

    @Test
    fun `a well-formed proximity is forwarded and a malformed one is dropped`() {
        val geocoder = service(VANCOUVER_FEATURE)

        runBlocking { geocoder.geocode("Dallas", autocomplete = true, proximity = "-96.797,32.777", limit = null) }
        runBlocking { geocoder.geocode("Dallas", autocomplete = true, proximity = "not-a-point", limit = null) }

        assertEquals("-96.797,32.777", queryParamOf(requests[0], "proximity"))
        assertNull(queryParamOf(requests[1], "proximity"))
    }

    @Test
    fun `autocomplete off is forwarded as false`() {
        runBlocking { service(VANCOUVER_FEATURE).geocode("Vancouver", autocomplete = false, proximity = null, limit = null) }

        assertEquals("false", queryParamOf(requests.single(), "autocomplete"))
    }

    @Test
    fun `an upstream failure is its own outcome`() {
        val outcome =
            runBlocking { failingService().geocode("Vancouver", autocomplete = true, proximity = null, limit = null) }

        assertEquals(GeocodeOutcome.Unavailable, outcome)
    }

    @Test
    fun `a found place serializes with the dto`() {
        val outcome = runBlocking { service(VANCOUVER_FEATURE).geocode("Vancouver", autocomplete = true, proximity = null, limit = null) }

        val result = resultsOf(outcome).single().jsonObject
        assertEquals("place.1", result["id"]!!.jsonPrimitive.content)
        assertEquals("Vancouver, British Columbia, Canada", result["place_name"]!!.jsonPrimitive.content)
        assertEquals("place", result["place_type"]!!.jsonPrimitive.content)
        assertEquals(-123.1207, result["lng"]!!.jsonPrimitive.double)
        assertEquals(49.2827, result["lat"]!!.jsonPrimitive.double)
        // A place with no reported extent omits the key entirely rather than
        // shipping a null the client has to distinguish from an empty box.
        assertNull(result["bbox"])
    }

    @Test
    fun `a region serializes its extent as west south east north`() {
        val outcome = runBlocking { service(UTAH_FEATURE).geocode("Utah", autocomplete = true, proximity = null, limit = null) }

        assertEquals(
            listOf(-114.052, 36.997, -109.041, 42.001),
            resultsOf(outcome)
                .single()
                .jsonObject["bbox"]!!
                .jsonArray
                .map { it.jsonPrimitive.double },
        )
    }

    private fun resultsOf(outcome: GeocodeOutcome) =
        Json
            .parseToJsonElement(encodeApiJson(assertIs<GeocodeOutcome.Found>(outcome).response))
            .jsonObject["results"]!!
            .jsonArray

    private fun queryParamOf(
        request: HttpRequestData,
        name: String,
    ): String? = request.url.parameters[name]

    private fun service(body: String): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = "pk.test",
                httpClient =
                    HttpClient(
                        MockEngine { request ->
                            requests += request
                            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ),
            ),
        )

    private fun failingService(): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = "pk.test",
                httpClient =
                    HttpClient(
                        MockEngine { request ->
                            requests += request
                            respondError(HttpStatusCode.InternalServerError)
                        },
                    ),
            ),
        )

    private fun serviceWithoutToken(): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = null,
                httpClient = HttpClient(MockEngine { request -> requests += request; respondError(HttpStatusCode.InternalServerError) }),
            ),
        )
}
```

- [ ] **Step 2: Rewrite `GeocodeRoutesTest` as a status test**

Replace the whole of `backend/src/test/kotlin/ca/floo/roadtrip/route/GeocodeRoutesTest.kt`. Its two DTO cases moved to `GeocodeServiceTest` in Step 1; what belongs here is the status mapping, which nothing tested before:

```kotlin
package ca.floo.roadtrip.route

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.route.api.geocode.geocodeRoutes
import ca.floo.roadtrip.service.geocode.GeocodeService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

private const val OVER_MAX_QUERY_LENGTH = 201
private const val VANCOUVER_FEATURE =
    """{"features":[{"id":"place.1","place_name":"Vancouver, British Columbia, Canada",""" +
        """"place_type":["place"],"center":[-123.1207,49.2827]}]}"""

class GeocodeRoutesTest {
    @Test
    fun `a found place answers 200 with the results envelope`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(okService()) } }

            val resp = client.get("/api/geocode?q=Vancouver")

            assertEquals(HttpStatusCode.OK, resp.status)
            val results = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["results"]!!.jsonArray
            assertEquals("place.1", results.single().jsonObject["id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `an unset token answers 503 geocoding_unavailable`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(unconfiguredService()) } }

            val resp = client.get("/api/geocode?q=Vancouver")

            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertEquals("geocoding_unavailable", errorOf(resp.bodyAsText()))
        }

    @Test
    fun `a blank query answers 400 bad_query`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(okService()) } }

            val resp = client.get("/api/geocode?q=")

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_query", errorOf(resp.bodyAsText()))
        }

    @Test
    fun `an over-long query answers 400 bad_query`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(okService()) } }

            val resp = client.get("/api/geocode?q=${"x".repeat(OVER_MAX_QUERY_LENGTH)}")

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            assertEquals("bad_query", errorOf(resp.bodyAsText()))
        }

    @Test
    fun `an upstream failure answers 503 geocoding_unavailable`() =
        testApplication {
            application { routeTestApplication { geocodeRoutes(failingService()) } }

            val resp = client.get("/api/geocode?q=Vancouver")

            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            assertEquals("geocoding_unavailable", errorOf(resp.bodyAsText()))
        }

    private fun errorOf(body: String): String =
        Json
            .parseToJsonElement(body)
            .jsonObject["error"]!!
            .jsonPrimitive.content

    private fun okService(): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = "pk.test",
                httpClient =
                    HttpClient(
                        MockEngine {
                            respond(VANCOUVER_FEATURE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    ),
            ),
        )

    private fun failingService(): GeocodeService =
        GeocodeService(
            MapboxGeocoder(
                token = "pk.test",
                httpClient = HttpClient(MockEngine { respondError(HttpStatusCode.InternalServerError) }),
            ),
        )

    private fun unconfiguredService(): GeocodeService = GeocodeService(MapboxGeocoder(token = null))
}
```

- [ ] **Step 3: Run both to verify they fail**

Run: `./gradlew :backend:test --tests '*GeocodeServiceTest*' --tests '*GeocodeRoutesTest*' --offline -q`
Expected: FAIL to compile — `Unresolved reference: GeocodeService` / `GeocodeOutcome`.

- [ ] **Step 4: Write the service**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/geocode/GeocodeService.kt`. The regex, the length cap, the limit bounds, and the DTO mapper all move here verbatim from the route file:

```kotlin
package ca.floo.roadtrip.service.geocode

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.model.api.GeocodeResponseDto
import ca.floo.roadtrip.model.api.GeocodeResultDto
import ca.floo.roadtrip.model.routing.GeocodeResult
import ca.floo.roadtrip.support.GeocodeException

private val lngLatRegex = Regex("""^-?\d{1,3}(\.\d{1,8})?,-?\d{1,3}(\.\d{1,8})?$""")
private const val MAX_QUERY_LENGTH = 200
private const val DEFAULT_GEOCODE_LIMIT = 5
private const val MIN_GEOCODE_LIMIT = 1
private const val MAX_GEOCODE_LIMIT = 10

private val geocodeLimitRange = MIN_GEOCODE_LIMIT..MAX_GEOCODE_LIMIT

/** What forward-geocoding came to. The route maps each arm to a status. */
internal sealed interface GeocodeOutcome {
    data class Found(
        val response: GeocodeResponseDto,
    ) : GeocodeOutcome

    /** No `roadtrip.mapbox.token`: geocoding is off, not failing. */
    data object NotConfigured : GeocodeOutcome

    data object BadQuery : GeocodeOutcome

    data object Unavailable : GeocodeOutcome
}

/**
 * Forward-geocoding as a use case: whether it is available at all, what a
 * usable query is, how many results the vendor may return, and which proximity
 * hints are well formed. The only place `client/mapbox`'s geocoder is used
 * outside DI.
 */
internal class GeocodeService(
    private val geocoder: MapboxGeocoder,
) {
    suspend fun geocode(
        query: String,
        autocomplete: Boolean,
        proximity: String?,
        limit: Int?,
    ): GeocodeOutcome {
        if (!geocoder.configured) return GeocodeOutcome.NotConfigured

        val trimmed = query.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_QUERY_LENGTH) return GeocodeOutcome.BadQuery

        val results =
            try {
                geocoder.forward(
                    trimmed,
                    autocomplete = autocomplete,
                    proximity = proximity?.takeIf { lngLatRegex.matches(it) },
                    limit = (limit ?: DEFAULT_GEOCODE_LIMIT).coerceIn(geocodeLimitRange),
                )
            } catch (e: GeocodeException) {
                return GeocodeOutcome.Unavailable
            }

        return GeocodeOutcome.Found(geocodeResponseDto(results))
    }
}

internal fun geocodeResponseDto(results: List<GeocodeResult>): GeocodeResponseDto =
    GeocodeResponseDto(
        results =
            results.map { result ->
                GeocodeResultDto(
                    id = result.id,
                    placeName = result.placeName,
                    placeType = result.placeType,
                    lng = result.lng,
                    lat = result.lat,
                    bbox = result.bbox?.let { listOf(it.west, it.south, it.east, it.north) },
                )
            },
    )
```

If detekt flags the unused `e` in the catch, replace the binding with `catch (_: GeocodeException)` — the route's version had the same unused binding, so match whatever the ruleset already tolerates in this repo.

- [ ] **Step 5: Make `GeocodeRoutes` an HTTP shell**

Replace the whole of `backend/src/main/kotlin/ca/floo/roadtrip/route/api/geocode/GeocodeRoutes.kt`:

```kotlin
package ca.floo.roadtrip.route.api.geocode

import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.queryParam
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.trimmedQuery
import ca.floo.roadtrip.service.geocode.GeocodeOutcome
import ca.floo.roadtrip.service.geocode.GeocodeService
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val AUTOCOMPLETE_OFF = "0"

/**
 * GET /api/geocode?q=<text>[&autocomplete=0][&proximity=lng,lat][&limit=N]
 *
 * Backend proxy for Mapbox forward-geocoding. The frontend's top-bar search
 * debounces input then hits this endpoint for autofill suggestions.
 *
 * Response shape (also documented for swagger):
 *   { "results": [ { id, place_name, place_type, lng, lat, bbox? }, ... ] }
 *
 * `bbox` is `[west, south, east, north]` and is present only for a feature the
 * upstream reports an extent for — a country, a region, a district, a place, a
 * park with a footprint. It is what lets the client frame a searched-for REGION
 * as an area instead of flying to an arbitrary point inside it.
 */
fun Route.geocodeRoutes(geocodeService: GeocodeService) {
    route("/api") {
        get("/geocode") {
            val outcome =
                geocodeService.geocode(
                    query = call.trimmedQuery("q"),
                    autocomplete = call.queryParam("autocomplete") != AUTOCOMPLETE_OFF,
                    proximity = call.queryParam("proximity"),
                    limit = call.queryParam("limit")?.toIntOrNull(),
                )

            when (outcome) {
                GeocodeOutcome.NotConfigured ->
                    call.respondApiError(
                        "geocoding_unavailable",
                        HttpStatusCode.ServiceUnavailable,
                        detail = "roadtrip.mapbox.token not set",
                    )
                GeocodeOutcome.BadQuery -> call.respondApiError("bad_query", HttpStatusCode.BadRequest)
                GeocodeOutcome.Unavailable -> call.respondApiError("geocoding_unavailable", HttpStatusCode.ServiceUnavailable)
                is GeocodeOutcome.Found -> call.respondEncodedJson(outcome.response)
            }
        }.access(RouteAccess.Anonymous)
    }
}
```

- [ ] **Step 6: Rewire DI**

In `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`, add `import ca.floo.roadtrip.service.geocode.GeocodeService` and register it beside the routing singles from Task 5:

```kotlin
        single { GeocodeService(get<MapboxGeocoder>()) }
```

adding `import ca.floo.roadtrip.client.mapbox.MapboxGeocoder` if this file does not already import it.

In `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, replace the fully-qualified injection

```kotlin
    val mapboxGeocoder: ca.floo.roadtrip.client.mapbox.MapboxGeocoder by inject()
```

with

```kotlin
    val geocodeService: GeocodeService by inject()
```

adding `import ca.floo.roadtrip.service.geocode.GeocodeService`, and change the mount:

```kotlin
        geocodeRoutes(geocodeService)
```

- [ ] **Step 7: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS. `grep -rn "client.mapbox.MapboxGeocoder" backend/src/main/kotlin` now matches only `di/InfraModule.kt`, `di/ServiceModule.kt`, and `service/geocode/GeocodeService.kt`.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip backend/src/test/kotlin/ca/floo/roadtrip
git commit -m "$(cat <<'EOF'
refactor(route): a geocode service behind /api/geocode

Refs #742

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Two route literals become config, the paging bounds become one object, and the dead mappers go

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/config/CampsiteAvailabilityConfig.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/common/ListPaging.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/AvailabilityConfig.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/config/AuthConfig.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/CampsiteRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/availability/AvailabilityDashboardRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/route/api/availability/AvailabilityWatchRoutes.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraAvailabilityProvider.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/RecGovAvailabilityProvider.kt`, `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, `backend/src/main/resources/application.yaml`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/RoadtripRuntimeConfigTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/CampsiteRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraObservationsTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/RecGovObservationsTest.kt`

**Interfaces:**
- Consumes: `ConfigSection.section(name)`, `ConfigSection.value(name): String?`, `ConfigSection.csvSet(name): Set<String>` (a YAML list flattens to a comma-joined string, so `csvSet` reads one); `IpRateLimiter(perMinute: Int, nowMs: () -> Long = System::currentTimeMillis)`; `upstreamAvailabilityError(cause, httpStatus, blockedStatuses, blockedMessageMarker): AvailabilityProviderError`.
- Produces:
  - `data class CampsiteAvailabilityConfig(val ipRateLimitPerMinute: Int)` with `companion object { val default; fun fromConfig(config: ConfigSection): CampsiteAvailabilityConfig }`
  - `AvailabilityConfig.campsite: CampsiteAvailabilityConfig` (new fourth constructor property, before `bulk`'s sibling — declared last so nothing positional breaks)
  - `AuthConfig.allowedConnections: Set<String>` (last constructor property, defaulted to `setOf("google-oauth2")`)
  - `AuthRouteWiring.allowedConnections: Set<String>` (new required property)
  - `internal object ListPaging { const val DEFAULT_LIMIT = 100; const val MIN_LIMIT = 1; const val MAX_LIMIT = 500; const val DEFAULT_OFFSET = 0; const val MIN_OFFSET = 0; val limitRange: IntRange }`
  - `internal fun Route.campsiteRoutes(controller: CampsiteAvailabilityController, config: CampsiteAvailabilityConfig, rateLimit: IpRateLimiter = IpRateLimiter(perMinute = config.ipRateLimitPerMinute))`
  - `mapAspiraUpstreamError` and `mapRecgovUpstreamError` no longer exist

- [ ] **Step 1: Write the failing config tests**

Append to `backend/src/test/kotlin/ca/floo/roadtrip/RoadtripRuntimeConfigTest.kt`, inside the class, adding the imports `ca.floo.roadtrip.config.AuthConfig`, `ca.floo.roadtrip.config.AvailabilityConfig`, `ca.floo.roadtrip.config.ConfigSection`, and `kotlin.test.assertFailsWith` (some are already present):

```kotlin
    @Test
    fun `the campsite IP rate limit defaults to the literal it replaced`() {
        assertEquals(30, availabilityConfig(emptyMap()).campsite.ipRateLimitPerMinute)
    }

    @Test
    fun `the campsite IP rate limit is tunable`() {
        assertEquals(
            7,
            availabilityConfig(mapOf("roadtrip.availability.campsite.ip-rate-limit-per-minute" to "7"))
                .campsite.ipRateLimitPerMinute,
        )
    }

    @Test
    fun `a campsite IP rate limit below one is refused at boot`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                availabilityConfig(mapOf("roadtrip.availability.campsite.ip-rate-limit-per-minute" to "0"))
            }

        assertEquals("campsite ip-rate-limit-per-minute must be >= 1 (got 0)", err.message)
    }

    @Test
    fun `the shipped availability yaml states the campsite limit beside the bulk one`() {
        assertEquals(30, AppConfig.fromProperties(ApplicationProperties.load()).availability.campsite.ipRateLimitPerMinute)
    }

    @Test
    fun `allowed auth connections default to the literal they replaced`() {
        assertEquals(setOf("google-oauth2"), authConfig(emptyMap())!!.allowedConnections)
    }

    @Test
    fun `allowed auth connections are configurable per environment`() {
        assertEquals(
            setOf("google-oauth2", "windowslive"),
            authConfig(mapOf("roadtrip.auth.allowed-connections" to "google-oauth2,windowslive"))!!.allowedConnections,
        )
    }

    private fun availabilityConfig(overrides: Map<String, String>): AvailabilityConfig =
        AvailabilityConfig.fromConfig(
            ConfigSection(
                mapOf(
                    "roadtrip.availability.force-pull-cooldown" to "60s",
                    "roadtrip.availability.provider-cooldown" to "5m",
                ) + overrides,
            ).section("roadtrip").section("availability"),
        )

    private fun authConfig(overrides: Map<String, String>): AuthConfig? =
        AuthConfig.fromConfig(
            ConfigSection(
                mapOf(
                    "roadtrip.auth.provider" to "oidc",
                    "roadtrip.auth.providers.oidc.issuer" to "https://test.example",
                    "roadtrip.auth.providers.oidc.client-id" to "test-client",
                    "roadtrip.auth.providers.oidc.client-secret" to "test-secret",
                ) + overrides,
            ).section("roadtrip").section("auth"),
        )
```

Add `import ca.floo.roadtrip.config.ApplicationProperties` and `import ca.floo.roadtrip.config.AppConfig` if this file does not already have them — the fourth test reads the shipped `application.yaml` through the same loader the app boots with, which is how the YAML key itself gets asserted.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :backend:test --tests '*RoadtripRuntimeConfigTest*' --offline -q`
Expected: FAIL to compile — `Unresolved reference: campsite`, `Unresolved reference: allowedConnections`.

- [ ] **Step 3: Write the campsite availability config**

Create `backend/src/main/kotlin/ca/floo/roadtrip/config/CampsiteAvailabilityConfig.kt`:

```kotlin
package ca.floo.roadtrip.config

/**
 * Caps on the single-POI availability read. Its costlier bulk sibling has been
 * tunable since it shipped; this one was a route literal, which meant the
 * cheaper endpoint was the one an operator could not throttle.
 */
data class CampsiteAvailabilityConfig(
    val ipRateLimitPerMinute: Int,
) {
    init {
        require(ipRateLimitPerMinute >= 1) {
            "campsite ip-rate-limit-per-minute must be >= 1 (got $ipRateLimitPerMinute)"
        }
    }

    companion object {
        private const val DEFAULT_IP_RATE_LIMIT_PER_MINUTE = 30

        val default = CampsiteAvailabilityConfig(ipRateLimitPerMinute = DEFAULT_IP_RATE_LIMIT_PER_MINUTE)

        fun fromConfig(config: ConfigSection): CampsiteAvailabilityConfig =
            CampsiteAvailabilityConfig(
                ipRateLimitPerMinute =
                    config.value("ip-rate-limit-per-minute")?.toInt()
                        ?: default.ipRateLimitPerMinute,
            )
    }
}
```

In `backend/src/main/kotlin/ca/floo/roadtrip/config/AvailabilityConfig.kt`, add the property and its parse:

```kotlin
data class AvailabilityConfig(
    val forcePullCooldown: Duration,
    val providerCooldown: Duration,
    val poller: AvailabilityPollerConfig,
    val bulk: BulkAvailabilityConfig,
    val campsite: CampsiteAvailabilityConfig,
) {
    companion object {
        fun fromConfig(config: ConfigSection): AvailabilityConfig =
            AvailabilityConfig(
                forcePullCooldown = config.requiredDuration("force-pull-cooldown"),
                providerCooldown = config.requiredDuration("provider-cooldown"),
                poller = AvailabilityPollerConfig.fromConfig(config.section("poller")),
                bulk = BulkAvailabilityConfig.fromConfig(config.section("bulk")),
                campsite = CampsiteAvailabilityConfig.fromConfig(config.section("campsite")),
            )
    }
}
```

- [ ] **Step 4: Put the auth connection allowlist in config**

In `backend/src/main/kotlin/ca/floo/roadtrip/config/AuthConfig.kt`, add the key constant and the default beside the others near the top:

```kotlin
private const val ALLOWED_CONNECTIONS_KEY = "allowed-connections"

/** The connection slugs `/auth/login` may forward. Environment-dependent: the
 *  claims dialect already varies by vendor, and so do their connection names. */
private val defaultAllowedConnections = setOf("google-oauth2")
```

add the property as the last constructor parameter:

```kotlin
    val roleGrants: Map<Role, Set<String>>,
    /**
     * Connection slugs `/auth/login` may forward to the provider. Unknown values
     * are dropped, so the browser falls through to the provider's own login page.
     */
    val allowedConnections: Set<String> = defaultAllowedConnections,
) {
```

and set it in `fromConfig`, just after `roleGrants = roleGrants,`:

```kotlin
                roleGrants = roleGrants,
                allowedConnections = config.csvSet(ALLOWED_CONNECTIONS_KEY).ifEmpty { defaultAllowedConnections },
```

- [ ] **Step 5: Run the config test to verify it passes**

Run: `./gradlew :backend:test --tests '*RoadtripRuntimeConfigTest*' --offline -q`
Expected: FAIL on `the shipped availability yaml states the campsite limit beside the bulk one` only — the key is not in `application.yaml` yet, so it falls back to the default 30 and the test actually passes; the other five pass too. If the whole class is green, go on. Either way, Step 6 writes the YAML so the value is stated rather than implied.

- [ ] **Step 6: State both keys in `application.yaml`**

In `backend/src/main/resources/application.yaml`, add the `campsite` block under `roadtrip.availability`, directly after the `bulk` block's `ip-rate-limit-per-minute: 10` line and at the same indentation as `bulk`:

```yaml
    campsite:
      # Per-IP ceiling on the single-POI availability read. Higher than bulk's
      # because one call is one POI, not a fan-out — but still an anonymous
      # endpoint that reaches upstream vendors, so an operator can turn it down.
      ip-rate-limit-per-minute: 30
```

and add the connection allowlist under `roadtrip.auth`, after the `embedded-domain:` line:

```yaml
    # Connection slugs /auth/login may forward to the provider. Unknown values
    # are dropped rather than rejected, so the browser falls through to the
    # provider's own login page. Varies by vendor, like the claims dialect.
    allowed-connections:
      - google-oauth2
```

- [ ] **Step 7: Make the campsite route read its limit from config**

In `backend/src/main/kotlin/ca/floo/roadtrip/route/api/pois/CampsiteRoutes.kt`, add `import ca.floo.roadtrip.config.CampsiteAvailabilityConfig`, delete the line

```kotlin
private const val IP_RATE_LIMIT_PER_MINUTE = 30
```

and change the signature:

```kotlin
internal fun Route.campsiteRoutes(
    controller: CampsiteAvailabilityController,
    config: CampsiteAvailabilityConfig,
    rateLimit: IpRateLimiter = IpRateLimiter(perMinute = config.ipRateLimitPerMinute),
) {
```

The route body is unchanged — `rateLimit.allow(...)` already reads the injected limiter.

In `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, change the mount:

```kotlin
        campsiteRoutes(campsiteController, config.availability.campsite)
```

- [ ] **Step 8: Make `AuthRoutes` read the allowlist from its wiring**

In `backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt`, delete the file-private value

```kotlin
/** Allowlist of connection slugs that may be forwarded to the provider.
 *  Unknown values are silently dropped — the browser falls through to the
 *  provider's own login page rather than getting an error. */
private val allowedConnections = setOf("google-oauth2")
```

change the one read of it inside `get("/login")`:

```kotlin
            val connection = call.queryParam(CONNECTION_PARAM)?.takeIf { it in auth.allowedConnections }
```

and add the property to `AuthRouteWiring`, after `isEmbeddedLogin`:

```kotlin
    /** Connection slugs /auth/login may forward. Unknown values are silently
     *  dropped — the browser falls through to the provider's own login page. */
    val allowedConnections: Set<String>,
```

In `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`, pass it in the `AuthRouteWiring(...)` constructed by `authRouteWiring`, after `isEmbeddedLogin`:

```kotlin
        isEmbeddedLogin = dialectRegistry.supportsEmbeddedLoginFor(authConfig.provider),
        allowedConnections = authConfig.allowedConnections,
```

In `backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt`, add the same argument to the test's `AuthRouteWiring(...)`:

```kotlin
        isEmbeddedLogin = true,
        allowedConnections = setOf("google-oauth2"),
```

- [ ] **Step 9: Collapse the duplicated paging bounds**

Create `backend/src/main/kotlin/ca/floo/roadtrip/route/common/ListPaging.kt`:

```kotlin
package ca.floo.roadtrip.route.common

/**
 * The paging bounds every list endpoint shares. Two route files stated the same
 * five numbers; a third would have made it three.
 */
internal object ListPaging {
    const val DEFAULT_LIMIT = 100
    const val MIN_LIMIT = 1
    const val MAX_LIMIT = 500
    const val DEFAULT_OFFSET = 0
    const val MIN_OFFSET = 0

    val limitRange: IntRange = MIN_LIMIT..MAX_LIMIT
}
```

In `backend/src/main/kotlin/ca/floo/roadtrip/route/api/availability/AvailabilityWatchRoutes.kt`, add `import ca.floo.roadtrip.route.common.ListPaging`, delete the five `private const val` paging lines and `private val listLimitRange`, and change the two call sites:

```kotlin
                val limit = call.boundedIntQuery("limit", ListPaging.DEFAULT_LIMIT, ListPaging.limitRange)
                val offset = call.intQueryAtLeast("offset", ListPaging.DEFAULT_OFFSET, ListPaging.MIN_OFFSET)
```

In `backend/src/main/kotlin/ca/floo/roadtrip/route/api/availability/AvailabilityDashboardRoutes.kt`, add the same import, delete `DEFAULT_LIST_LIMIT`, `MIN_LIST_LIMIT`, `MAX_LIST_LIMIT`, `DEFAULT_LIST_OFFSET`, `MIN_LIST_OFFSET` and `listLimitRange`, and keep the dashboard's own snapshot bounds, re-pointed at the shared minimum:

```kotlin
private const val SNAPSHOT_DEFAULT_LIMIT = 200
private const val SNAPSHOT_MAX_LIMIT = 1000

private val snapshotLimitRange = ListPaging.MIN_LIMIT..SNAPSHOT_MAX_LIMIT
```

Then replace the three `boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)` call sites with `boundedIntQuery("limit", ListPaging.DEFAULT_LIMIT, ListPaging.limitRange)` and the single offset call site with `intQueryAtLeast("offset", ListPaging.DEFAULT_OFFSET, ListPaging.MIN_OFFSET)`. The snapshot call site keeps `boundedIntQuery("limit", SNAPSHOT_DEFAULT_LIMIT, snapshotLimitRange)`.

- [ ] **Step 10: Delete the two dead mappers and the Ktor imports they held**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraAvailabilityProvider.kt`, delete the whole `internal fun mapAspiraUpstreamError(...)` function and these three now-unused imports:

```kotlin
import ca.floo.roadtrip.model.api.AvailabilityErrorDto
import ca.floo.roadtrip.service.api.availabilityErrorDto
import io.ktor.http.HttpStatusCode
```

`aspiraBlockedStatuses` and `WAF_MESSAGE_MARKER` stay — `runWithErrorMapping` still uses both.

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/RecGovAvailabilityProvider.kt`, delete the whole `internal fun mapRecgovUpstreamError(...)` function and the same three imports. If deleting the function leaves `HTTP_TOO_MANY_REQUESTS` or any other constant unused in either file, the compiler says so — delete those too.

In `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/AspiraObservationsTest.kt`, delete the whole `aspira upstream mapper uses availability error dto renderer` test and any import it alone used (`AspiraException`, `encodeApiJson`, `jsonObject`, `jsonPrimitive`, `int`, `Json` — the compiler names the ones that are now unused). Its assertion is already covered end to end by `ProviderUpstreamErrorMappingTest.aspira classification`, which drives the real adapter and pins `503 -> UpstreamBlocked` and the WAF-message case.

In `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/provider/RecGovObservationsTest.kt`:

In `upstream error propagates so route layer can 503`, delete the last three lines and keep the `require`:

```kotlin
        require(ex is AvailabilityProviderError.RateLimited) { "expected RateLimited, got $ex" }
    }
```

In `a typed client 429 maps to the rate-limited outcome`, delete the final `assertEquals(...)` line, keeping the `require`.

Replace `5xx maps to upstream_5xx` — the only case that tested nothing but the dead mapper — with the production classifier it should have been asserting:

```kotlin
    @Test
    fun `a vendor 5xx classifies as upstream unavailable`() {
        assertIs<AvailabilityProviderError.UpstreamUnavailable>(
            upstreamAvailabilityError(cause = IllegalStateException("connection reset"), httpStatus = 500),
        )
    }
```

adding `kotlin.test.assertIs` and removing `kotlin.test.assertEquals` if nothing else in the file uses it.

- [ ] **Step 11: Point `CampsiteRoutesTest` at the config parameter**

In `backend/src/test/kotlin/ca/floo/roadtrip/route/CampsiteRoutesTest.kt`, add `import ca.floo.roadtrip.config.CampsiteAvailabilityConfig` and replace the helper — the explicit-limiter case is exactly what proves the route no longer owns the number:

```kotlin
    private fun Route.campsiteRoutesUnderTest(
        providers: List<AvailabilityProvider> = listOf(ServingRecgovProvider()),
        rateLimit: IpRateLimiter? = null,
        config: CampsiteAvailabilityConfig = CampsiteAvailabilityConfig.default,
    ) {
        if (rateLimit != null) {
            campsiteRoutes(controller(providers), config, rateLimit)
        } else {
            campsiteRoutes(controller(providers), config)
        }
    }
```

and add the case that the default limiter reads the config, beside the existing throttle test (which passes `IpRateLimiter(perMinute = 1, nowMs = { 0L })`):

```kotlin
    @Test
    fun `the default limiter reads its ceiling from config`() =
        testApplication {
            application {
                routeTestApplication {
                    campsiteRoutesUnderTest(config = CampsiteAvailabilityConfig(ipRateLimitPerMinute = 1))
                }
            }
            val (poiId, _) = seedRecgovPoiWithCampsite()

            assertEquals(HttpStatusCode.OK, client.get("/api/pois/$poiId/campsites/availability").status)
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                client.get("/api/pois/$poiId/campsites/availability").status,
                "a config of 1/min must throttle the second call, not the thirty-first",
            )
        }
```

If the existing throttle test seeds differently (its own POI helper, its own assertions on the `ip_throttled` body), mirror that spelling rather than the sketch above — the point is only that the limit comes from `CampsiteAvailabilityConfig`.

- [ ] **Step 12: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS, including `LayeringGuardTest.only the OIDC redirect builder names Ktor under service` — the two provider files were the other two entries, and they are gone.

- [ ] **Step 13: Commit**

```bash
git add backend/src/main backend/src/test
git commit -m "$(cat <<'EOF'
refactor(route): route policy becomes config, and the dead mappers go

Refs #742

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Docs

**Files:**
- Modify: `docs/backend-architecture.md`, `AGENTS.md`, `docs/reservation-providers.md`, `rfcs/0004-ingestion-controller.md`
- Test: none — documentation only. The gate still runs, because `LayeringGuardTest` and `RoadtripRuntimeConfigTest` are what make these sentences true.

**Interfaces:**
- Consumes: every name this plan produced — `UnitOfWork`, `Repos`, `JooqUnitOfWork`, `JooqUnitOfWork.autocommit`, `RoutePlanService`, `RouteResponseMapper`, `GeocodeService`, `CampsiteAvailabilityConfig`, `AuthConfig.allowedConnections`, `ListPaging`, `LayeringGuardTest`.
- Produces: nothing at compile time. `docs/adding-a-reservation-provider.md` is deliberately untouched — it never named either deleted mapper (Resolution 8).

- [ ] **Step 1: Give `docs/backend-architecture.md` a Transactions section**

Insert this as a new top-level section immediately before `## Application Wiring`:

````markdown
## Transactions

One type opens transactions: `JooqUnitOfWork`, in `repo/`. Everything else asks
for one.

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
- Repos do not open transactions. The single-repo mirror in
  `UserBookingCredentialsRepo` is the one exception, and it is a repo, which is
  the layer allowed to.
- `JooqUnitOfWork.autocommit` is the non-transactional handle bundle, for
  callers that batch without a transaction. The import is non-transactional by
  decision (`rfcs/0004-ingestion-controller.md`), so its terminal sinks take
  `autocommit` explicitly: the choice is visible at the call site rather than
  implied by which constructor was used.
- No type outside `repo/`, `db/`, `di/InfraModule.kt`, and `di/RepoModule.kt`
  names `org.jooq` — exceptions included. A repo that can fail on a PostGIS
  fault translates `org.jooq.exception.DataAccessException` into a domain type
  (`CorridorUnavailableException`) or handles it, so no caller ever catches
  jOOQ's. `LayeringGuardTest` fails the build on any other `org.jooq` import.
````

- [ ] **Step 2: Correct the `DSLContext` sentence in the Repos boundary rule**

In the same file, in `### Repos`, replace

```markdown
Repos own SQL. If code needs SQL, jOOQ, table names, JSONB casts, materialized
view refreshes, link-table writes, or persistence mapping, put it behind a repo
method. Services and ETLs ask for capability through methods; they do not pass
`DSLContext` around to make their own queries.
```

with

```markdown
Repos own SQL. If code needs SQL, jOOQ, table names, JSONB casts, materialized
view refreshes, link-table writes, or persistence mapping, put it behind a repo
method. Services and ETLs ask for capability through methods; they never hold or
pass a `DSLContext`. A service that needs several writes to land together takes
a `UnitOfWork` (see Transactions); one that does not takes the repos it uses.
```

- [ ] **Step 3: Name the new wiring in Application Wiring and the ETL Flow**

In `## Application Wiring`, after the five-step list and before "Construction-heavy wiring belongs in…", add:

```markdown
`repoModule` registers every repo as a singleton, plus `JooqUnitOfWork` and its
two projections: `UnitOfWork` for callers that transact and `Repos` for callers
that batch on the autocommit context. Route wiring resolves repos from there; it
does not construct them, and it holds no `DSLContext`.
```

In `## ETL Flow`, after "The ETL framework owns orchestration and run lifecycle. Vendor ETLs parse, validate, and transform their upstream inputs. Persistence stays in repos.", add:

```markdown
A terminal ETL's sink factory is `(Repos) -> TerminalSink`: the binding is handed
repo handles, never a connection context, and production binds them to
`JooqUnitOfWork.autocommit`. A phase's counts cross back as
`ImportPhaseCounts`, a `@Serializable` model; `IngestRunRepo.completePhase`
encodes it into the `ingest_runs.counts` JSONB column, so no jOOQ type is an
interchange value between two service methods.
```

- [ ] **Step 4: Add the one-line rule to `AGENTS.md`**

In `AGENTS.md`, under "Backend layering rules:", add as the second bullet (right after the typed-DTO bullet):

```markdown
- Services take a `UnitOfWork` (when several writes must land together) or the repo handles they use — never a `DSLContext`. `org.jooq` appears only under `repo/`, `db/`, and the two infrastructure DI modules; `LayeringGuardTest` fails the build otherwise.
```

- [ ] **Step 5: Correct the "same transaction" sentence in `docs/reservation-providers.md`**

Replace

```markdown
mirrors every save, rename and clear into them in the same transaction, so a
```

with

```markdown
mirrors every save, rename and clear into them inside its own transaction, so a
```

and append one sentence to the end of that paragraph, after "…until then do not stop writing them.":

```markdown
That atomicity is the repo's own: `UserSettingsService` still writes its three
tables without a transaction, and making that save atomic is a separate change.
```

- [ ] **Step 6: Add the decision-log line to `rfcs/0004-ingestion-controller.md`**

Append one row to the decision-log table at the bottom (the one whose last row is `| 8 | 2026-06-06 | No scheduler/cron in v1. | …`):

```markdown
| 9   | 2026-09-11 | Terminal sinks bind to `Repos` from `JooqUnitOfWork.autocommit`; the import stays non-transactional.                             | Decision 6's future work is still future work. Binding sinks to an explicit autocommit bundle makes "this import is not atomic" visible at the wiring site instead of implied by a shared `DSLContext`, and leaves step 6 a one-line swap to `UnitOfWork.run` when it is taken. |
```

- [ ] **Step 7: Run the gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt --offline -q`
Expected: PASS (unchanged by this task; run it so the commit is known-green).

- [ ] **Step 8: Commit**

```bash
git add docs AGENTS.md rfcs
git commit -m "$(cat <<'EOF'
docs: the unit of work, the HTTP shell, and the layering guard

Refs #739
Refs #742

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
EOF
)"
```

---

## Manual verification (after Task 8)

On the local stack, with `make data-import` already run:

- [ ] `GET /api/route?coords=-123.1207,49.2827;-114.0719,51.0447` returns one `LineString` feature with `distance_m`, `duration_s`, `legs`, and `waypoints`.
- [ ] The same call with `&radius_miles=5` returns two features, the second `{"role":"corridor","radius_miles":5.0}` with a `Polygon` geometry — and the map still draws the corridor.
- [ ] `GET /api/route?coords=-123.1207,49.2827;-123.1207,49.2827` returns 400 `duplicate_adjacent` with detail `points 1 and 0 are identical`.
- [ ] `GET /api/route?coords=-123.1207,49.2827;-114.0719,51.0447&radius_miles=999` returns 400 `bad_radius`.
- [ ] `GET /api/geocode?q=Vancouver` returns results; `?q=` returns 400 `bad_query`; with `MAPBOX_TOKEN` unset, both return 503 `geocoding_unavailable`.
- [ ] The top-bar search still autofills, and searching a region still frames its extent rather than flying to a point.
- [ ] Create, update, and delete a watch through the UI; the poller links follow (check `availability_watch_poller`), and a deleted watch leaves none.
- [ ] Sign out and back in: `/api/me` reports the same user, and `/auth/login?connection=google-oauth2` still forwards while `?connection=bogus` falls through to the provider page.
- [ ] Run an admin import from `/admin`: the run completes, `ingest_runs.counts` carries `import_run_id`/`seen`/`swept`/`terminal_etl` (plus the two campsite fields on a campsite phase), and the dashboard renders them unchanged.
- [ ] Restart the backend mid-run, then boot again: the orphaned `started` parent row reads `aborted` with notes containing `boot recovery`, and no ghost shows on the dashboard.
- [ ] `GET /api/pois/<id>/campsites/availability` thirty-one times inside a minute returns 503 `ip_throttled` on the last one.
- [ ] `make qa`.

---

## Self-review

**1. Spec coverage.**

| Spec item | Task |
| --- | --- |
| `UnitOfWork` / `Repos` / `JooqUnitOfWork` + `autocommit`, DI registration | 1 |
| `RepoModule` gains `UserIdentityRepo`, `UserSessionRepo`, `ImportRunRepo`, `IngestRunRepo` | 1 |
| `AvailabilityWatchService` on `UnitOfWork`; `RepoWatchAlertScope` replaces `TransactionalWatchAlertScope` | 2 |
| `UserProvisioningService` on `UnitOfWork`, helpers unchanged | 2 |
| `PollerBackfill`: injected read repos + `unitOfWork.run` per watch | 2 |
| `TerminalEtlDefinition` on `Repos`; the four sink factories read `repos.*` | 3 |
| `EtlOrchestrator` takes `ImportRunRepo`, registry required | 3 |
| `IngestController` takes its repos; `runImport(): ImportPhaseCounts`; `completePhase` encodes JSONB in the repo | 3 |
| `sweepStaleIngestRuns` → `IngestRunRepo.abortStaleStartedRows` from the DI boot hook; `BootRecovery.kt` deleted | 3 |
| `RouteCorridorRepo` throws a domain exception; the POIs-on-route repo serves empty on a topology fault and logs it | 4 |
| `RouteModule` on repo singletons, no `ctx` | 4 |
| `LayeringGuardTest`: jOOQ allowlist, `io.ktor`-under-`service/` allowlist, no repo import under `route/` | 4 |
| `RoutePlanService` + `RouteResponseMapper` + `RouteRoutes` | 5 |
| `GeocodeService` + `GeocodeRoutes` | 6 |
| Campsite IP rate limit as config, default 30, `>= 1` | 7 |
| `AuthConfig.allowedConnections`, default `["google-oauth2"]` | 7 |
| One `ListPaging`; the dashboard keeps its snapshot limits | 7 |
| Both dead mappers and their `io.ktor` imports deleted; their tests assert the production path | 7 |
| Docs: `backend-architecture.md` (Transactions, Application Wiring, ETL Flow), `AGENTS.md`, `reservation-providers.md`, RFC 0004 | 8 |

Every bullet in the spec's Testing section has a step: `JooqUnitOfWorkTest` (1); `AvailabilityWatchServiceTest`, `UserProvisioningServiceTest`, `PollerBackfillTest`, `AvailabilityWatchRoutesTest`, `AuthControllerTest`, `AuthRoutesTest` (2); `EtlOrchestratorCampflareTest`, `IngestControllerTest`, `AdminIngestRoutesTest`, `IngestRunRepoTest` (3); `RouteCorridorRepoTest`, `PoiServingRepoTest`, `LayeringGuardTest` (4); `RoutePlanServiceTest`, `RouteResponseMapperTest` (5); `GeocodeServiceTest`, `GeocodeRoutesTest` (6); `CampsiteRoutesTest`, `RoadtripRuntimeConfigTest` (7); the live checks above.

Two spec items are deliberately reshaped, both argued at the top under Resolutions: `CorridorUnavailableException` lives in `support/` rather than `model/domain/routing/` (1), and `RoutePlanService` returns a sealed result rather than throwing `RoutingException(DUPLICATE_WAYPOINTS)` (2). Everything else follows the spec's letter. The spec's out-of-scope list is respected: no task makes the import transactional or the settings three-table save atomic, none introduces repo interfaces or retires a detached-context fake, and `LOGIN_FLOW_MAX_AGE_SECONDS`, `RECENT_RUNS_LIMIT`, path segments, cookie names, and route error codes are untouched.

**2. Placeholder scan.** Every code step carries the code. The two places that say "the compiler names them" are about deleting imports a deletion orphans, which is mechanical and specific; the one place that says "mirror that spelling" (Task 7 Step 11) names the exact existing test being mirrored and the exact property being asserted. No step says TBD, "add error handling", or "similar to Task N" — Task 2's and Task 3's repeated construction blocks are written out in full at each site for exactly that reason.

**3. Type consistency.** `UnitOfWork.run(block: (Repos) -> T): T` is spelled identically in Tasks 1, 2, 3, and the docs. `Repos`' member names (`watches`, `pollers`, `users`, `userIdentities`, `campgrounds`, `campsites`, `teslaSuperchargers`, `planetFitnessLocations`, `importRuns`, `ingestRuns`) are declared once in Task 1 and read by those names in Tasks 2 and 3. `JooqUnitOfWork.autocommit` is the same name in Tasks 1, 3, and 8. `ImportPhaseCounts`' six fields and their `@SerialName`s are declared in Task 3 Step 4 and asserted by those wire names in Task 3 Step 1. `RoutePlan(directions, waypoints, corridorRadiusMiles, corridorGeoJson)` is constructed in Task 5's service and read by field in Task 5's mapper and tests. `RoutePlanResult`'s four arms are named identically in the service, the route's `when`, and `RoutePlanServiceTest`. `GeocodeOutcome`'s four arms likewise in the service, the route, `GeocodeServiceTest`, and `GeocodeRoutesTest`. `CampsiteAvailabilityConfig.ipRateLimitPerMinute` is one spelling across the config class, `AvailabilityConfig`, `CampsiteRoutes`, `RouteModule`, `application.yaml` (`ip-rate-limit-per-minute`), and both tests. `ListPaging`'s five constants and `limitRange` are used by those names in both route files. `allowedConnections` is the same name on `AuthConfig`, `AuthRouteWiring`, `RouteModule`, `AuthRoutes`, and both tests.

**4. Ordering.** Task 4's `LayeringGuardTest` cannot be green before Tasks 2 and 3 have removed the service-layer jOOQ imports, and cannot be green before Task 4's own edits to `RouteModule` and the two services — which is why it is written in Task 4 and not Task 1. Task 5's byte-for-byte pin is written and proven green against the *old* code in Steps 1–2, before any production change in that task. Task 7's `io.ktor` guard assertion only passes once the two dead mappers are gone, which is the same task.

**5. No migration.** Nothing in these eight tasks adds, edits, or replays a Flyway migration. `V61__booking_alias_indexes.sql` remains the highest.
