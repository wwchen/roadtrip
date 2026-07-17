# Koin DI Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace manual DI (RoadtripBootContext/RoadtripRuntime) with Koin-Ktor, eliminate registry classes via `List<T>` injection with `Dispatchable<K>` dispatch.

**Architecture:** Bottom-up migration in 9 commits within a single PR. Introduce Koin + Dispatchable, then migrate infra → repos → services → routes, delete old wiring, convert tests, and restructure folders.

**Tech Stack:** Koin 4.x (`koin-ktor`, `koin-core`, `koin-test`, `koin-test-junit5`), Ktor 3.5.1, Kotlin 2.3.10, JUnit 5

## Global Constraints

- Kotlin 2.3.10, JVM toolchain 25
- Ktor 3.5.1
- JUnit 5 (Jupiter) for tests
- ktlint 1.3.1 formatting enforced
- All code compiles and tests pass after each commit
- `internal` visibility for services/repos (existing convention)
- No changes to database schema or migrations

---

### Task 1: Add Koin Dependencies and Install Plugin

**Files:**
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`

**Interfaces:**
- Consumes: nothing
- Produces: Koin plugin installed on Application; `koinModule()` extension function stub (empty module initially); Koin available on classpath for all subsequent tasks

- [ ] **Step 1: Add Koin dependencies to build.gradle.kts**

In `backend/build.gradle.kts`, add a version constant after the existing `val junitVersion` line:

```kotlin
val koinVersion = "4.0.4"
```

Add these dependencies in the `dependencies` block after the `implementation("com.resend:resend-java:$resendVersion")` line:

```kotlin
// Koin DI framework for Ktor
implementation("io.insert-koin:koin-ktor:$koinVersion")
implementation("io.insert-koin:koin-core:$koinVersion")
implementation("io.insert-koin:koin-logger-slf4j:$koinVersion")
```

Add test dependencies after the existing `testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")` line:

```kotlin
testImplementation("io.insert-koin:koin-test:$koinVersion")
testImplementation("io.insert-koin:koin-test-junit5:$koinVersion")
```

- [ ] **Step 2: Install Koin plugin in Application.module()**

In `Main.kt`, add imports:

```kotlin
import io.ktor.server.application.install
import org.koin.ktor.plugin.Koin
import org.koin.dsl.module
```

Add Koin installation as the first line inside `Application.module()`, before the `val properties` line:

```kotlin
install(Koin) {
    modules(module { })
}
```

This installs Koin with an empty module — a no-op that proves the dependency wires correctly.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "feat: add Koin dependencies and install empty plugin"
```

---

### Task 2: Dispatchable Convention and Typed Keys

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/Dispatchable.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/TriggerKind.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProviderId.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilitySourceId.kt`
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/service/DispatchableTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `Dispatchable<K>` interface with `canHandle(key: K): Boolean`
  - `List<T>.firstHandlerFor(key: K): T?` extension
  - `List<T>.allHandlersFor(key: K): List<T>` extension
  - `TriggerKind` enum: `SLACK_NOTIFY`, `EMAIL_NOTIFY`, `ATC`
  - `AlertProviderId` enum: `INTERNAL_POLLER`
  - `AvailabilitySourceId` value class wrapping `String`

- [ ] **Step 1: Write the Dispatchable test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/service/DispatchableTest.kt`:

```kotlin
package ca.floo.roadtrip.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DispatchableTest {
    private data class FakeHandler(val key: String) : Dispatchable<String> {
        override fun canHandle(key: String): Boolean = key == this.key
    }

    private val handlers = listOf(FakeHandler("a"), FakeHandler("b"), FakeHandler("c"))

    @Test
    fun `firstHandlerFor returns first match`() {
        assertEquals(FakeHandler("b"), handlers.firstHandlerFor("b"))
    }

    @Test
    fun `firstHandlerFor returns null when no match`() {
        assertNull(handlers.firstHandlerFor("z"))
    }

    @Test
    fun `allHandlersFor returns all matches`() {
        val multi = handlers + FakeHandler("b")
        assertEquals(2, multi.allHandlersFor("b").size)
    }

    @Test
    fun `allHandlersFor returns empty for no match`() {
        assertEquals(emptyList(), handlers.allHandlersFor("z"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.DispatchableTest"`
Expected: FAIL — `Dispatchable` not found

- [ ] **Step 3: Implement Dispatchable**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/Dispatchable.kt`:

```kotlin
package ca.floo.roadtrip.service

interface Dispatchable<K> {
    fun canHandle(key: K): Boolean
}

fun <K, T : Dispatchable<K>> List<T>.firstHandlerFor(key: K): T? =
    firstOrNull { it.canHandle(key) }

fun <K, T : Dispatchable<K>> List<T>.allHandlersFor(key: K): List<T> =
    filter { it.canHandle(key) }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.DispatchableTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: Create TriggerKind enum**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/TriggerKind.kt`:

```kotlin
package ca.floo.roadtrip.service.availability

enum class TriggerKind(val slug: String) {
    SLACK_NOTIFY("slack_notify"),
    EMAIL_NOTIFY("email_notify"),
    ATC("atc"),
    ;

    companion object {
        private val bySlug = entries.associateBy { it.slug }
        fun fromSlug(slug: String): TriggerKind? = bySlug[slug]
    }
}
```

- [ ] **Step 6: Create AlertProviderId enum**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProviderId.kt`:

```kotlin
package ca.floo.roadtrip.service.availability.alert

enum class AlertProviderId(val slug: String) {
    INTERNAL_POLLER("internal_poller"),
}
```

- [ ] **Step 7: Create AvailabilitySourceId value class**

Create `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilitySourceId.kt`:

```kotlin
package ca.floo.roadtrip.service.availability.provider

@JvmInline
value class AvailabilitySourceId(val slug: String)
```

- [ ] **Step 8: Verify full compilation**

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/Dispatchable.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/TriggerKind.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProviderId.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilitySourceId.kt
git add backend/src/test/kotlin/ca/floo/roadtrip/service/DispatchableTest.kt
git commit -m "feat: add Dispatchable interface and typed dispatch keys"
```

---

### Task 3: infraModule — Config, DataSource, Clients

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`

**Interfaces:**
- Consumes: Koin plugin installed (Task 1)
- Produces: Koin module providing: `Map<String, String>` (properties), `AppConfig`, `DataSource`, `DSLContext`, `MapboxGeocoder`, `MapboxDirections`, `RouteCache`, `PoiRegistry`, `IngestController`, `CoroutineScope`, all HTTP clients (`RecGovAvailabilityClient`, `AspiraAvailabilityClient`, `ReserveAmericaAvailabilityClient`, `ReserveCaliforniaAvailabilityClient`, `CampflareAvailabilityClient`), `SlackClient`/`ResendEmailClient`

- [ ] **Step 1: Create InfraModule.kt**

Create `backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt`:

```kotlin
package ca.floo.roadtrip.di

import ca.floo.roadtrip.clients.aspira.HttpAspiraAvailabilityClient
import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.clients.campflare.HttpCampflareAvailabilityClient
import ca.floo.roadtrip.clients.companion.HttpRecGovAtcExecutor
import ca.floo.roadtrip.clients.companion.RecGovAtcExecutor
import ca.floo.roadtrip.clients.mapbox.MapboxDirections
import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.clients.recgov.HttpRecgovAvailabilityClient
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.HttpReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.HttpReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.ReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.config.ApplicationProperties
import ca.floo.roadtrip.config.ConfigSection
import ca.floo.roadtrip.config.DbConfig
import ca.floo.roadtrip.db.dataSourceFor
import ca.floo.roadtrip.db.dsl
import ca.floo.roadtrip.db.migrate
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.ApiCacheRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.etl.framework.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.sweepStaleIngestRuns
import ca.floo.roadtrip.service.routing.RouteCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jooq.DSLContext
import org.koin.dsl.module
import java.io.File
import javax.sql.DataSource

private const val STATIC_DIR_KEY = "static-dir"
private const val DEFAULT_STATIC_DIR = "."
private const val POI_REGISTRY_RESOURCE_KEY = "resource"
private const val POI_REGISTRY_PATH_KEY = "path"
private const val MAPBOX_TOKEN_KEY = "token"
private const val DEFAULT_POI_REGISTRY_RESOURCE = "poi-registry.yaml"
private const val RAW_DATA_DIR = "data/raw"

val infraModule = module {
    single { ApplicationProperties.load(get()) }
    single { AppConfig.fromProperties(get<Map<String, String>>()) }

    single<DataSource> {
        val properties: Map<String, String> = get()
        val roadtripConfig = ConfigSection(properties).section("roadtrip")
        val ds = dataSourceFor(DbConfig.fromConfig(roadtripConfig.section("db")))
        migrate(ds)
        ds
    }

    single<DSLContext> { dsl(get<DataSource>()) }

    single<File>(qualifier = named("staticDir")) {
        val properties: Map<String, String> = get()
        val roadtripConfig = ConfigSection(properties).section("roadtrip")
        File(roadtripConfig.valueOrDefault(STATIC_DIR_KEY, DEFAULT_STATIC_DIR))
    }

    single<PoiRegistry> {
        val properties: Map<String, String> = get()
        val roadtripConfig = ConfigSection(properties).section("roadtrip")
        val config = roadtripConfig.section("poi-registry")
        val staticDir: File = get(qualifier = named("staticDir"))
        val pathOverride = config.value(POI_REGISTRY_PATH_KEY)
        if (pathOverride != null) {
            PoiRegistry.load(staticDir.resolveConfiguredPath(pathOverride))
        } else {
            PoiRegistry.loadResource(
                config.valueOrDefault(POI_REGISTRY_RESOURCE_KEY, DEFAULT_POI_REGISTRY_RESOURCE),
            )
        }
    }

    // HTTP clients — each gets onClose for lifecycle
    single<RecGovAvailabilityClient> { HttpRecgovAvailabilityClient() } onClose { it?.close() }
    single { HttpAspiraAvailabilityClient() } onClose { it?.close() }
    single<ReserveAmericaAvailabilityClient> { HttpReserveAmericaAvailabilityClient() } onClose { it?.close() }
    single<ReserveCaliforniaAvailabilityClient> { HttpReserveCaliforniaAvailabilityClient() } onClose { it?.close() }
    single<CampflareAvailabilityClient> {
        val config: AppConfig = get()
        HttpCampflareAvailabilityClient(
            apiBaseUrl = config.campflare.apiBaseUrl,
            apiKey = config.campflare.apiKey,
        )
    } onClose { it?.close() }

    // Mapbox
    single {
        val properties: Map<String, String> = get()
        val token = ConfigSection(properties).section("roadtrip").section("mapbox").value(MAPBOX_TOKEN_KEY)
        MapboxGeocoder(token = token)
    }
    single {
        val properties: Map<String, String> = get()
        val token = ConfigSection(properties).section("roadtrip").section("mapbox").value(MAPBOX_TOKEN_KEY)
        MapboxDirections(token = token)
    }

    // Route cache
    single {
        val config: AppConfig = get()
        RouteCache(
            directions = get<MapboxDirections>(),
            ttl = config.cache.ttlFor(ApiCacheEntity.ROUTE),
            persistentCache = ApiCacheRepo(get()),
        )
    }

    // ETL / Ingest
    single {
        val ctx: DSLContext = get()
        sweepStaleIngestRuns(ctx)
        val staticDir: File = get(qualifier = named("staticDir"))
        IngestController(
            ctx = ctx,
            etl = EtlOrchestrator(
                ctx = ctx,
                rawDir = staticDir.resolveConfiguredPath(RAW_DATA_DIR),
                poiRegistry = get(),
                canonicalViews = CanonicalViewRepo(ctx),
            ),
            importTargets = importTargetsFromRegistry(get()),
        )
    }

    // Shared scheduler coroutine scope
    single {
        CoroutineScope(Dispatchers.IO + SupervisorJob())
    } onClose { scope ->
        scope?.cancel()
    }

    // Rec.gov ATC executor (conditional)
    single<RecGovAtcExecutor?> {
        val config: AppConfig = get()
        config.booking.recgovAtc
            .takeIf { it.companionEnabled }
            ?.let(::HttpRecGovAtcExecutor)
    }
}

private fun File.resolveConfiguredPath(path: String): File {
    val configured = File(path)
    return if (configured.isAbsolute) configured else File(this, path)
}
```

Note: This module references `named()` — add the import `import org.koin.core.qualifier.named` at the top.

- [ ] **Step 2: Wire infraModule into Application.module()**

In `Main.kt`, replace the empty `install(Koin)` block with:

```kotlin
install(Koin) {
    modules(infraModule)
}
```

Add import: `import ca.floo.roadtrip.di.infraModule`

The existing `createRoadtripBootContext` and `startRoadtripRuntime` calls remain for now — both wiring systems coexist temporarily.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/di/InfraModule.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "feat: add infraModule with config, datasource, and clients"
```

---

### Task 4: repoModule — All Repositories

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` (add module to Koin install)

**Interfaces:**
- Consumes: `infraModule` providing `DSLContext`
- Produces: Koin bindings for all repo classes: `CampsiteRepo`, `CampsiteProviderRepo`, `AvailabilityRepo`, `AvailabilityWatchRepo`, `AvailabilityPollerRepo`, `AvailabilityRunRepo`, `AvailabilityFetchCallRepo`, `PoiServingRepo`, `ApiCacheRepo`, `CampgroundRepo`, `TeslaSuperchargerRepo`, `PlanetFitnessLocationRepo`, `RouteCorridorRepo`, `CanonicalViewRepo`, `AdminIngestReadRepo`, `AvailabilityWatchTargetRepo`

- [ ] **Step 1: Create RepoModule.kt**

Create `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt`:

```kotlin
package ca.floo.roadtrip.di

import ca.floo.roadtrip.repo.AdminIngestReadRepo
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import org.koin.dsl.module

val repoModule = module {
    includes(infraModule)

    single { CampsiteRepo(get()) }
    single { CampsiteProviderRepo(get()) }
    single { AvailabilityRepo(get()) }
    single { AvailabilityWatchRepo(get()) }
    single { AvailabilityPollerRepo(get()) }
    single { AvailabilityRunRepo(get()) }
    single { AvailabilityFetchCallRepo(get()) }
    single { PoiServingRepo(get()) }
    single { CampgroundRepo(get()) }
    single { TeslaSuperchargerRepo(get()) }
    single { PlanetFitnessLocationRepo(get()) }
    single { RouteCorridorRepo(get()) }
    single { CanonicalViewRepo(get()) }
    single { AdminIngestReadRepo(get()) }
    single { AvailabilityWatchTargetRepo(get()) }
}
```

- [ ] **Step 2: Add repoModule to Koin install**

In `Main.kt`, update the Koin install to:

```kotlin
install(Koin) {
    modules(infraModule, repoModule)
}
```

Add import: `import ca.floo.roadtrip.di.repoModule`

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "feat: add repoModule with all repository bindings"
```

---

### Task 5: serviceModule — Services and List<T> Multi-bindings

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProvider.kt` (extend Dispatchable)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/TriggerActionHandler.kt` (extend Dispatchable)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProvider.kt` (extend Dispatchable)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/BookingProvider.kt` (extend Dispatchable)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` (add module to Koin install)

**Interfaces:**
- Consumes: `infraModule`, `repoModule`, `Dispatchable<K>`, typed keys (Task 2)
- Produces:
  - `AvailabilityProvider` extends `Dispatchable<AvailabilitySourceId>`
  - `TriggerActionHandler` extends `Dispatchable<TriggerKind>`
  - `AlertProvider` extends `Dispatchable<AlertProviderId>`
  - `BookingProvider` extends `Dispatchable<BookingTarget>`
  - Koin multi-bindings: `List<AvailabilityProvider>`, `List<TriggerActionHandler>`, `List<AlertProvider>`, `List<BookingProvider>`
  - All service singletons

- [ ] **Step 1: Add Dispatchable to AvailabilityProvider**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProvider.kt`, add import:

```kotlin
import ca.floo.roadtrip.service.Dispatchable
```

Change the interface declaration from:

```kotlin
interface AvailabilityProvider {
```

to:

```kotlin
interface AvailabilityProvider : Dispatchable<AvailabilitySourceId> {
```

Add a default `canHandle` implementation using the existing `id` and a new `sources` property. Each adapter will declare which source slugs it handles:

```kotlin
val sources: Set<AvailabilitySourceId> get() = emptySet()

override fun canHandle(key: AvailabilitySourceId): Boolean =
    isEnabled() && key in sources
```

- [ ] **Step 2: Add Dispatchable to TriggerActionHandler**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/TriggerActionHandler.kt`, add imports:

```kotlin
import ca.floo.roadtrip.service.Dispatchable
```

Change the interface declaration to:

```kotlin
internal interface TriggerActionHandler : Dispatchable<TriggerKind> {
```

Add default implementation:

```kotlin
override fun canHandle(key: TriggerKind): Boolean = key.slug in kinds
```

- [ ] **Step 3: Add Dispatchable to AlertProvider**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProvider.kt`, add imports:

```kotlin
import ca.floo.roadtrip.service.Dispatchable
```

Change the interface declaration to:

```kotlin
internal interface AlertProvider : Dispatchable<AlertProviderId> {
```

Add default implementation:

```kotlin
override fun canHandle(key: AlertProviderId): Boolean = key.slug == id
```

- [ ] **Step 4: Add Dispatchable to BookingProvider**

In `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/BookingProvider.kt`, add imports:

```kotlin
import ca.floo.roadtrip.service.Dispatchable
```

Change the interface declaration to:

```kotlin
internal interface BookingProvider : Dispatchable<BookingTarget> {
```

Add default implementation:

```kotlin
override fun canHandle(key: BookingTarget): Boolean = key.providerId == id
```

- [ ] **Step 5: Implement sources property on each AvailabilityProvider adapter**

Each adapter needs to declare its sources. The sources are set at construction time via a new constructor parameter. For example, in `RecGovAvailabilityProvider`:

```kotlin
class RecGovAvailabilityProvider(
    private val client: RecGovAvailabilityClient,
    private val enabled: Boolean,
    override val sources: Set<AvailabilitySourceId>,
) : AvailabilityProvider { ... }
```

Repeat for `CampflareAvailabilityProvider`, `AspiraAvailabilityProvider`, `ReserveAmericaAvailabilityProvider`, `ReserveCaliforniaAvailabilityProvider`. Each adapter's constructor adds a `sources: Set<AvailabilitySourceId>` parameter and assigns it to `override val sources`.

- [ ] **Step 6: Create ServiceModule.kt**

Create `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`. This is the largest module — it wires all services and builds the multi-bound lists. The availability provider list factory reads `PoiRegistry` to determine source assignments (same logic currently in `AvailabilityProviderRegistry.fromPoiRegistry()`):

```kotlin
package ca.floo.roadtrip.di

import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.service.availability.*
import ca.floo.roadtrip.service.availability.alert.*
import ca.floo.roadtrip.service.availability.provider.*
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.*
import ca.floo.roadtrip.service.availability.provider.adapters.campflare.*
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.*
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.*
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.*
import ca.floo.roadtrip.service.booking.*
import ca.floo.roadtrip.service.booking.adapters.RecGovBookingProvider
import ca.floo.roadtrip.service.notification.common.*
import ca.floo.roadtrip.service.notification.email.*
import ca.floo.roadtrip.service.notification.slack.*
import ca.floo.roadtrip.service.poi.*
import ca.floo.roadtrip.service.poi.campground.*
import ca.floo.roadtrip.service.ratelimit.*
import ca.floo.roadtrip.service.routing.*
import ca.floo.roadtrip.service.scheduler.*
import ca.floo.roadtrip.service.scheduler.framework.*
import ca.floo.roadtrip.service.scheduler.jobs.*
import org.koin.dsl.module

val serviceModule = module {
    includes(repoModule, infraModule)

    // Notification services
    single { SlackNotificationService(get<AppConfig>().slack) }
    single { EmailNotificationService(get<AppConfig>().email) }
    single {
        NotificationFanout(listOf(get<SlackNotificationService>(), get<EmailNotificationService>()))
    } onClose { it?.close() }

    // Availability providers (List<AvailabilityProvider>)
    single<List<AvailabilityProvider>> { buildAvailabilityProviders(get(), get(), get(), get(), get(), get()) }

    // Alert providers (List<AlertProvider>)
    single { AvailabilityPollerMembership(get<WatchScopeResolver>(), get<DbAvailabilityTargetResolver>()) }
    single<List<AlertProvider>> { listOf(InternalPollerAlertProvider(get<AvailabilityPollerMembership>())) }

    // Trigger action handlers (List<TriggerActionHandler>)
    single<List<TriggerActionHandler>> {
        listOf(
            NotifyTriggerActionHandler(
                notifications = get<NotificationFanout>(),
                appRootUrl = get<AppConfig>().webApp?.rootUrl,
            ),
            AtcTriggerActionHandler(
                bookings = get(),
                bookingTargets = get(),
                notifications = get<NotificationFanout>(),
            ),
        )
    }

    // Booking providers (List<BookingProvider>)
    single<List<BookingProvider>> {
        listOfNotNull(
            get<RecGovAtcExecutor?>()?.let(::RecGovBookingProvider),
        )
    }

    // Core services
    single { CoordinateTimeZones.warmUp(); AvailabilityDateResolver() }
    single { WatchScopeResolver(get<CampsiteRepo>()) }
    single {
        DbAvailabilityTargetResolver(
            providerRefs = get(),
            campsitesRepo = get(),
            availabilityProviders = get(),
            dateResolver = get(),
        )
    }
    single { AvailabilityBookingTargetResolver(get()) }
    single {
        WatchCapabilityService(
            availabilityTargets = get<DbAvailabilityTargetResolver>(),
            bookingTargets = get<AvailabilityBookingTargetResolver>(),
        )
    }
    single { WatchTriggerCapabilityValidator(scopeResolver = get(), capabilities = get()) }
    single { ProviderCooldownTracker(cooldown = get<AppConfig>().availability.providerCooldown) }
    single { FailoverAvailabilityFetcher(cooldowns = get()) }
    single { CampgroundAvailabilitySupport(providerRefs = get(), availabilityProviders = get()) }

    // Watch service
    single {
        AvailabilityWatchService(
            ctx = get(),
            alertProviders = get(),
            capabilityValidator = get(),
            lifecycleNotifications = DispatchingWatchLifecycleNotifications(
                dispatcher = get<WatchAlertDispatcher>(),
                scope = get(),
            ),
        )
    }

    // Alert dispatcher
    single {
        WatchAlertDispatcher(
            notifications = get<NotificationFanout>(),
            scopeResolver = get(),
            watches = get(),
            targets = get<DbAvailabilityTargetResolver>(),
            pois = get(),
            availability = get(),
            triggerActions = get(),
            grafanaRootUrl = get<AppConfig>().grafana?.rootUrl,
            appRootUrl = get<AppConfig>().webApp?.rootUrl,
        )
    }

    // Rate limiter
    single { VendorRateLimiter(get<AppConfig>().vendorRateLimit, get()) }

    // Scheduler (eager start)
    single(createdAtStart = true) {
        Scheduler(
            repo = get<AvailabilityPollerRepo>(),
            handler = AvailabilityPollExecutor(
                pollers = get(),
                campsitesRepo = get(),
                batcher = CatalogAvailabilityBatcher(),
                availability = get(),
                runs = get(),
                dateResolver = get(),
                targets = get<DbAvailabilityTargetResolver>(),
                fetchCalls = get(),
                limiter = get(),
                alertDispatcher = get(),
                failoverFetcher = get(),
            )::handle,
            name = "availability",
        ).also { it.start(get()) }
    }
    single(createdAtStart = true) { WatchReaper(get<AvailabilityPollerRepo>()).also { it.start(get()) } }
    single(createdAtStart = true) { PollerBackfill(get(), get<AvailabilityPollerMembership>()).also { it.run() } }

    // POI detail services
    single<List<PoiDetailService>> {
        listOf(
            CampgroundService(repo = get(), dateResolver = get(), availabilitySupport = get()),
            TeslaSuperchargerService(get()),
            PlanetFitnessLocationService(get()),
        )
    }

    // Slack interactivity (conditional)
    single<SlackInteractivityWiring?> {
        val config: AppConfig = get()
        config.slack?.signingSecret?.let { secret ->
            SlackInteractivityWiring(
                verifier = SlackSignatureVerifier(secret),
                handler = SlackInteractivityHandler(
                    watches = /* port impl referencing get<AvailabilityWatchService>() */,
                    slack = get<SlackNotificationService>(),
                ),
            )
        }
    }
}
```

Note: The exact implementation of `buildAvailabilityProviders()` function mirrors `AvailabilityProviderRegistry.fromPoiRegistry()` logic but returns `List<AvailabilityProvider>` instead. Each adapter now receives its source set in the constructor. This is the most complex part of the migration — implement as a private helper function within the file.

- [ ] **Step 7: Wire serviceModule into Main.kt**

Update the Koin install:

```kotlin
install(Koin) {
    modules(infraModule, repoModule, serviceModule)
}
```

Add import: `import ca.floo.roadtrip.di.serviceModule`

- [ ] **Step 8: Verify compilation and tests**

Run: `./gradlew :backend:compileKotlin`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :backend:test`
Expected: All existing tests still pass (old wiring path untouched)

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProvider.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/TriggerActionHandler.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProvider.kt
git add backend/src/main/kotlin/ca/floo/roadtrip/service/booking/BookingProvider.kt
git add -A backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/adapters/
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "feat: add serviceModule with List<T> multi-bindings and Dispatchable"
```

---

### Task 6: routeModule — Route Classes with by inject()

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/availability/AvailabilityWatchRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/availability/AvailabilityDashboardRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/pois/PoiRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/pois/CampsiteRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/pois/PoisOnRouteRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/route/RouteRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/geocode/GeocodeRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/health/HealthRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/admin/AdminIngestRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/slack/SlackRoutes.kt` (class)
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/docs/ApiDocsRoutes.kt` (class)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`

**Interfaces:**
- Consumes: `serviceModule` providing all service bindings
- Produces: Route classes implementing `KoinComponent` with `by inject()` dependencies; `RouteModule` binding them; updated `Application.module()` using route classes for registration

- [ ] **Step 1: Create route classes**

Each existing route extension function becomes a class. Example pattern for `AvailabilityWatchRoutes`:

```kotlin
package ca.floo.roadtrip.routes.api.availability

import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import io.ktor.server.routing.Route
import org.jooq.DSLContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AvailabilityWatchRoutes : KoinComponent {
    private val ctx: DSLContext by inject()
    private val watchService: AvailabilityWatchService by inject()
    private val watchCapabilities: WatchCapabilityService by inject()

    fun Route.register() {
        availabilityWatchRoutes(ctx, watchService, watchCapabilities)
    }
}
```

For the initial pass, each route class wraps the existing extension function call — no inline rewrite of route bodies. This minimizes diff while achieving the structural goal.

Repeat for every route group in `RoadtripApiRoutes.kt`: `AvailabilityDashboardRoutes`, `PoiRoutes`, `CampsiteRoutes`, `PoisOnRouteRoutes`, `RouteRoutes`, `GeocodeRoutes`, `HealthRoutes`, `AdminIngestRoutes`, `SlackRoutes`, `ApiDocsRoutes`.

For routes that currently construct services inline (e.g., `PoiRoutes` which builds `ReadPathProviderPoiReader`), move that construction into `serviceModule` as proper Koin bindings, and inject the result in the route class.

- [ ] **Step 2: Create RouteModule.kt**

Create `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`:

```kotlin
package ca.floo.roadtrip.di

import ca.floo.roadtrip.routes.api.admin.AdminIngestRoutes
import ca.floo.roadtrip.routes.api.availability.AvailabilityDashboardRoutes
import ca.floo.roadtrip.routes.api.availability.AvailabilityWatchRoutes
import ca.floo.roadtrip.routes.api.docs.ApiDocsRoutes
import ca.floo.roadtrip.routes.api.geocode.GeocodeRoutes
import ca.floo.roadtrip.routes.api.health.HealthRoutes
import ca.floo.roadtrip.routes.api.pois.CampsiteRoutes
import ca.floo.roadtrip.routes.api.pois.PoisOnRouteRoutes
import ca.floo.roadtrip.routes.api.pois.PoiRoutes
import ca.floo.roadtrip.routes.api.route.RouteRoutes
import ca.floo.roadtrip.routes.api.slack.SlackRoutes
import org.koin.dsl.module

val routeModule = module {
    includes(serviceModule)

    single { AvailabilityWatchRoutes() }
    single { AvailabilityDashboardRoutes() }
    single { PoiRoutes() }
    single { CampsiteRoutes() }
    single { PoisOnRouteRoutes() }
    single { RouteRoutes() }
    single { GeocodeRoutes() }
    single { HealthRoutes() }
    single { AdminIngestRoutes() }
    single { SlackRoutes() }
    single { ApiDocsRoutes() }
}
```

- [ ] **Step 3: Update Application.module() to use route classes**

In `Main.kt`, replace `registerRoadtripRoutes(runtime)` with route class registration:

```kotlin
fun Application.module() {
    install(Koin) {
        modules(infraModule, repoModule, serviceModule, routeModule)
    }
    installRoadtripPlugins()
    routing {
        val watchRoutes by inject<AvailabilityWatchRoutes>()
        with(watchRoutes) { register() }
        val dashboardRoutes by inject<AvailabilityDashboardRoutes>()
        with(dashboardRoutes) { register() }
        val poiRoutes by inject<PoiRoutes>()
        with(poiRoutes) { register() }
        val campsiteRoutes by inject<CampsiteRoutes>()
        with(campsiteRoutes) { register() }
        val poisOnRouteRoutes by inject<PoisOnRouteRoutes>()
        with(poisOnRouteRoutes) { register() }
        val routeRoutes by inject<RouteRoutes>()
        with(routeRoutes) { register() }
        val geocodeRoutes by inject<GeocodeRoutes>()
        with(geocodeRoutes) { register() }
        val healthRoutes by inject<HealthRoutes>()
        with(healthRoutes) { register() }
        val adminRoutes by inject<AdminIngestRoutes>()
        with(adminRoutes) { register() }
        val slackRoutes by inject<SlackRoutes>()
        with(slackRoutes) { register() }
        val docsRoutes by inject<ApiDocsRoutes>()
        with(docsRoutes) { register() }
        staticSiteRoutes(get<File>(qualifier = named("staticDir")))
    }
}
```

- [ ] **Step 4: Verify compilation and tests**

Run: `./gradlew :backend:test`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt
git add -A backend/src/main/kotlin/ca/floo/roadtrip/routes/
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "feat: add routeModule with KoinComponent route classes"
```

---

### Task 7: Delete Old Wiring

**Files:**
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/RoadtripBootContext.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/RoadtripRuntime.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/RoadtripRouting.kt` (keep `installRoadtripPlugins()` — move to its own file or inline)
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProviderRegistry.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/provider/AvailabilityProviderClients.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/alert/AlertProviderRegistry.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/TriggerActionRegistry.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/service/booking/BookingProviderRegistry.kt`
- Delete: `backend/src/main/kotlin/ca/floo/roadtrip/routes/api/RoadtripApiRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` (remove old boot/runtime code)
- Modify: `backend/build.gradle.kts` (update kover excludes)

**Interfaces:**
- Consumes: All Koin modules fully wired (Tasks 3-6)
- Produces: Clean `Application.module()` with no manual DI remnants; all references to `RoadtripRuntime` removed from codebase

- [ ] **Step 1: Clean up Main.kt**

Remove from `Main.kt`:
- The `createRoadtripBootContext` call
- The `startRoadtripRuntime` call
- The `monitor.subscribe(ApplicationStopping)` block
- The `registerRoadtripRoutes` call
- The `installOptionalShutdownThreadDump` call (move into a Koin `single(createdAtStart = true)` or leave as a standalone call before `install(Koin)`)

Final `Application.module()` should look like:

```kotlin
fun Application.module() {
    val baseConfig = environment.config
    install(Koin) {
        modules(infraModule(baseConfig), repoModule, serviceModule, routeModule)
    }
    installRoadtripPlugins()
    routing {
        // route registration (from Task 6)
    }
}
```

Note: `ApplicationProperties.load(baseConfig)` needs the Ktor `ApplicationConfig` passed in. Adjust `infraModule` to accept it as a parameter or provide it via Koin's `properties()` mechanism.

- [ ] **Step 2: Delete registry and wiring files**

Delete each file listed above. For callers that referenced registries (e.g., `DbAvailabilityTargetResolver` previously took `AvailabilityProviderRegistry`), update to take `List<AvailabilityProvider>` and use `firstHandlerFor()`.

Key caller changes:
- `DbAvailabilityTargetResolver`: `availabilityProviders: List<AvailabilityProvider>` → use `.firstHandlerFor(AvailabilitySourceId(source))`
- `CampgroundAvailabilitySupport`: same pattern
- `WatchAlertDispatcher`: `triggerActions: List<TriggerActionHandler>` → use `.allHandlersFor(TriggerKind.fromSlug(kind))`
- `AvailabilityWatchService`: `alertProviders: List<AlertProvider>` → use `.firstHandlerFor(AlertProviderId.INTERNAL_POLLER)`

- [ ] **Step 3: Move installRoadtripPlugins()**

Move `installRoadtripPlugins()` from `RoadtripRouting.kt` to a new file `backend/src/main/kotlin/ca/floo/roadtrip/config/RoadtripPlugins.kt` (or inline in Main.kt if small enough). Then delete `RoadtripRouting.kt`.

- [ ] **Step 4: Update kover excludes in build.gradle.kts**

Remove `RoadtripBootContext`, `RoadtripRuntime`, `RoadtripRuntimeKt`, `RoadtripRoutingKt` from the kover excludes since they no longer exist. Add `ca.floo.roadtrip.di.*` if you want to exclude DI module definitions from coverage.

- [ ] **Step 5: Verify compilation and tests**

Run: `./gradlew :backend:test`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: delete RoadtripRuntime, BootContext, and all registry classes"
```

---

### Task 8: Migrate Tests to KoinTest

**Files:**
- Create: `backend/src/test/kotlin/ca/floo/roadtrip/di/KoinModuleCheckTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityWatchRoutesTest.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/routes/PoiRoutesTest.kt`
- Modify: other route tests as needed
- Modify: service tests that manually construct dependency graphs

**Interfaces:**
- Consumes: All Koin modules, `koin-test-junit5` on test classpath
- Produces: Tests that use `KoinTest` with module overrides; `KoinModuleCheckTest` validating the full graph

- [ ] **Step 1: Create KoinModuleCheckTest**

Create `backend/src/test/kotlin/ca/floo/roadtrip/di/KoinModuleCheckTest.kt`:

```kotlin
package ca.floo.roadtrip.di

import org.junit.jupiter.api.Test
import org.koin.test.KoinTest
import org.koin.test.verify.verify

class KoinModuleCheckTest : KoinTest {
    @Test
    fun `all modules resolve without missing dependencies`() {
        routeModule.verify(
            extraTypes = listOf(
                io.ktor.server.config.ApplicationConfig::class,
            ),
        )
    }
}
```

Note: `verify()` statically checks the module graph at test time. Some types injected from Ktor (like `ApplicationConfig`) need to be declared as `extraTypes`.

- [ ] **Step 2: Migrate route tests**

For tests like `AvailabilityWatchRoutesTest` that currently construct services manually, convert to use Koin test modules with overrides. The existing `SharedDbTest` base class provides `ctx` — tests can load a test Koin module that binds `DSLContext` to the test instance's `ctx`:

```kotlin
class AvailabilityWatchRoutesTest : SharedDbTest(), KoinTest {
    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(
            module {
                single<DSLContext> { this@AvailabilityWatchRoutesTest.ctx }
                // other test bindings
            },
            repoModule,
            serviceModule,
        )
    }

    private val watchService: AvailabilityWatchService by inject()
    private val watchCapabilities: WatchCapabilityService by inject()
    // ... tests use injected services directly
}
```

Alternatively, for tests that intentionally use empty/fake registries, use `loadKoinModules` to override specific bindings.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew :backend:test`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add -A backend/src/test/
git commit -m "test: migrate to KoinTest with module verification"
```

---

### Task 9: Folder Restructure

**Files:** All backend source files — purely `git mv` and package declaration updates.

**Interfaces:**
- Consumes: All prior tasks complete, tests passing
- Produces: New folder layout per spec; all package declarations and imports updated

- [ ] **Step 1: Rename top-level packages**

```bash
# From backend/src/main/kotlin/ca/floo/roadtrip/
git mv clients client
git mv models model
git mv routes route
git mv exceptions support
```

Move `http/` contents into `support/`:
```bash
git mv http/* support/
```

Move `Dispatchable.kt` into `support/`:
```bash
git mv service/Dispatchable.kt support/
```

Move `SlackInteractivityWiring.kt`:
```bash
git mv SlackInteractivityWiring.kt service/notification/slack/
```

- [ ] **Step 2: Flatten adapter nesting**

```bash
# Flatten availability provider adapters
git mv service/availability/provider/adapters/recgov/* service/availability/
git mv service/availability/provider/adapters/aspira/* service/availability/
git mv service/availability/provider/adapters/campflare/* service/availability/
git mv service/availability/provider/adapters/reserveamerica/* service/availability/
git mv service/availability/provider/adapters/reservecalifornia/* service/availability/

# Flatten booking adapters
git mv service/booking/adapters/recgov/* service/booking/

# Remove empty adapter directories
rm -rf service/availability/provider/adapters
rm -rf service/booking/adapters
```

- [ ] **Step 3: Reorganize models**

```bash
# models/api/ → model/api/
# models/domain/ → model/domain/
# models/availability/ → model/wire/ (vendor wire formats)
# models/booking/ → model/domain/ or model/api/ based on usage
# models/metadata/ → model/wire/ or keep in model/metadata/
# models/routing/ → model/domain/
# models/etl/ → model/domain/
```

Exact moves depend on each file's usage — API DTOs go to `model/api/`, domain types to `model/domain/`, vendor wire formats to `model/wire/`.

- [ ] **Step 4: Update all package declarations and imports**

Use IDE-assisted rename or a script. Every `.kt` file that moved needs its `package` declaration updated. Every file that imports a moved class needs its `import` updated.

Suggested approach: run ktlint to catch any formatting issues, then compile to catch broken imports.

- [ ] **Step 5: Update test file paths**

Mirror the same moves in `src/test/kotlin/` — test packages should match source packages.

- [ ] **Step 6: Verify compilation and tests**

Run: `./gradlew :backend:test`
Expected: All tests pass

Run: `./gradlew :backend:ktlintCheck`
Expected: No formatting violations

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: restructure folder layout (git mv only, no logic changes)"
```

---

## Implementation Notes

### Koin Version

Use Koin 4.0.4 (latest stable as of writing). Key docs:
- Ktor integration: https://insert-koin.io/docs/reference/koin-ktor/ktor
- Testing: https://insert-koin.io/docs/reference/koin-test/testing
- Module includes: https://insert-koin.io/docs/reference/koin-core/modules#module-includes

### Worktree Setup

Before starting, create a worktree from latest master:

```bash
git worktree add ../roadtrip-koin-di master
cd ../roadtrip-koin-di
git checkout -b wc/koin-di-migration
```

### Ordering Constraint

Tasks 3-6 build on each other — each must compile before the next starts. Tasks 1-2 are independent of each other. Task 7 requires 3-6 complete. Task 8 requires 7 complete. Task 9 requires 8 complete.
