# Koin DI Migration

## Goal

Replace the manual dependency injection (RoadtripBootContext + RoadtripRuntime + startRoadtripRuntime()) with Koin-Ktor DI. Eliminate registry classes in favor of multi-bound `List<T>` injection with a shared `Dispatchable<K>` dispatch convention.

## Motivation

- **Reduce wiring boilerplate** — `startRoadtripRuntime()` is ~240 lines of construction in dependency order; every new service requires updating it.
- **Scope route dependencies** — routes currently receive the entire `RoadtripRuntime` god-object. Route classes with `by inject()` declare only what they use.
- **Uniform extensibility** — registries today are ad-hoc; new providers require touching registry construction. With `List<T>` injection, adding a provider means declaring it in the Koin module.

## Architecture

### Koin Module Organization

Four modules mirroring the dependency layers:

| Module | Contents |
|--------|----------|
| `infraModule` | AppConfig, DataSource, DSLContext, HTTP clients (Mapbox, Slack, Resend), CoroutineScope, PoiRegistry |
| `repoModule` | All repo classes (each takes DSLContext) |
| `serviceModule` | Business logic services + `List<T>` multi-bindings for dispatch |
| `routeModule` | Route classes implementing KoinComponent |

### Module Layering

Modules declare their dependencies via `includes()`, making the intended direction obvious in code:

```kotlin
val infraModule = module { ... }
val repoModule = module {
    includes(infraModule)
    // ...
}
val serviceModule = module {
    includes(repoModule, infraModule)
    // ...
}
val routeModule = module {
    includes(serviceModule)
    // ...
}
```

This doesn't prevent violations at runtime, but makes it visually obvious when a route injects infra directly or a repo reaches into service — leaky abstractions stand out in review. Compile-time enforcement via Gradle submodules is a possible future follow-up.

### Module Installation

```kotlin
fun Application.module() {
    install(Koin) {
        modules(infraModule, repoModule, serviceModule, routeModule)
    }
    installRoadtripPlugins()
    routing {
        val availabilityRoutes by inject<AvailabilityRoutes>()
        with(availabilityRoutes) { register() }
        // ...
    }
}
```

### Dispatchable Convention

A shared interface codifying the `canHandle()` + list-dispatch pattern used across all provider types:

```kotlin
interface Dispatchable<K> {
    fun canHandle(key: K): Boolean
}

fun <K, T : Dispatchable<K>> List<T>.firstHandlerFor(key: K): T? =
    firstOrNull { it.canHandle(key) }

fun <K, T : Dispatchable<K>> List<T>.allHandlersFor(key: K): List<T> =
    filter { it.canHandle(key) }
```

Each provider interface extends `Dispatchable` with its own key type:

| Interface | Key Type | Dispatch Semantics |
|-----------|----------|-------------------|
| `AvailabilityProvider` | `AvailabilitySourceId` (value class wrapping slug) | First match; secondary filter via `supportsRef()` |
| `TriggerActionHandler` | `TriggerKind` (enum) | All matches (multi-fire) |
| `AlertProvider` | `AlertProviderId` (enum) | First match |
| `BookingProvider` | `BookingTarget` | First match |
| `PoiDetailService` | `PoiType` (existing enum) | First match |

Compound dispatch (e.g., `canHandle(source) && supportsRef(ref)`) stays on the specific interface as additional methods. `Dispatchable` covers the primary routing key only.

### Registry Replacement

Each registry class is deleted. Its construction logic moves into the Koin module definition:

**AvailabilityProviderRegistry** → `single<List<AvailabilityProvider>> { ... }`
- One instance per tenant/host (Aspira per-host, ReserveAmerica per-tenant, others shared)
- Each instance's `canHandle(source)` checks its assigned source slugs
- Factory logic (reading PoiRegistry to determine sources) moves into the module or a factory function called within it

**TriggerActionRegistry** → `single<List<TriggerActionHandler>> { ... }`
- `NotifyTriggerActionHandler`, `AtcTriggerActionHandler`
- `canHandle(kind: TriggerKind)` = `kind in kinds`

**AlertProviderRegistry** → `single<List<AlertProvider>> { ... }`
- `InternalPollerAlertProvider` (v1)
- `canHandle(id: AlertProviderId)` = `id == this.id`

**BookingProviderRegistry** → `single<List<BookingProvider>> { ... }`
- `RecGovBookingProvider` (conditional on config)
- `canHandle(target)` = `target.providerId == id`

**PoiDetailService list** (currently inline in routes) → `single<List<PoiDetailService>> { ... }`
- `CampgroundService`, `TeslaSuperchargerService`, `PlanetFitnessLocationService`
- `canHandle(poiType)` dispatches by POI type

### Route Classes

Routes become classes implementing `KoinComponent`:

```kotlin
class AvailabilityRoutes : KoinComponent {
    private val watchService: AvailabilityWatchService by inject()
    private val providers: List<AvailabilityProvider> by inject()
    private val dateResolver: AvailabilityDateResolver by inject()

    fun Route.register() {
        get("/api/availability/{poiId}") { ... }
        post("/api/watches") { ... }
    }
}
```

Each route class declares exactly its dependencies — no more threading `RoadtripRuntime`.

### Conditional Bindings

Services that only exist when config enables them use config-gated binding in the module:

```kotlin
val serviceModule = module {
    val config = get<AppConfig>()
    if (config.atcEnabled) {
        single { RecGovBookingProvider(get()) }
    }
    if (config.slackEnabled) {
        single { SlackInteractivityWiring(get(), get()) }
    }
}
```

The injected `List<BookingProvider>` naturally excludes disabled providers since they're never bound.

### Lifecycle Management

Koin's `onClose` replaces manual `ApplicationStopping` subscription:

```kotlin
single {
    CoroutineScope(SupervisorJob() + Dispatchers.Default)
} onClose { scope ->
    scope?.cancel()
}
```

Services needing eager startup use `createdAtStart = true`:

```kotlin
single(createdAtStart = true) { Scheduler(get(), get()) }
```

`stopKoin()` (called by Ktor's Koin plugin on shutdown) triggers all `onClose` callbacks in reverse order. No manual `runtime.close()` chains.

### Additional Cleanup

- **`CampgroundAvailabilitySupport`** — currently constructed inline in routes; becomes a proper Koin binding in `serviceModule`.
- **`AvailabilityProviderClients`** — dissolves; individual clients become their own Koin bindings.
- **`RoadtripRouting.kt`** — `registerRoadtripRoutes(runtime)` is replaced by route class registration loop in `Application.module()`.
- **`NotificationFanout`** — if it dispatches to multiple channels, becomes `List<NotificationChannel>` with `Dispatchable`.
- **ETL registries** — `EtlOrchestrator.registry` and `joinerRegistry` follow the same `List<EtlAdapter>` pattern for consistency.

## Testing

Tests use Koin's `KoinTest` infrastructure:

```kotlin
class AvailabilityWatchServiceTest : KoinTest {
    @get:Rule
    val koinTestRule = KoinTestRule.create {
        modules(infraModule, repoModule, serviceModule)
    }

    private val watchService: AvailabilityWatchService by inject()

    @Test
    fun `creates watch`() { ... }
}
```

Mock overrides via `MockProviderRule`:

```kotlin
@get:Rule
val mockProvider = MockProviderRule.create { clazz ->
    Mockito.mock(clazz.java)
}

@Test
fun `alerts on availability`() {
    val mockSlack = declareMock<SlackClient>()
    // mockSlack injected wherever SlackClient is needed
}
```

Module graph verification:

```kotlin
class KoinModuleCheckTest : KoinTest {
    @Test
    fun `all modules resolve`() {
        koinApplication {
            modules(infraModule, repoModule, serviceModule, routeModule)
            checkModules()
        }
    }
}
```

## Folder Restructure

Final commit in the PR — purely file moves, no logic changes. Target layout:

```
ca/floo/roadtrip/
├── Main.kt
├── di/                  ← NEW: Koin module definitions
│   ├── InfraModule.kt
│   ├── RepoModule.kt
│   ├── ServiceModule.kt
│   └── RouteModule.kt
├── client/              ← rename from clients/ (singular convention)
│   ├── aspira/
│   ├── campflare/
│   ├── companion/
│   ├── mapbox/
│   ├── recgov/
│   ├── resend/
│   ├── reserveamerica/
│   ├── reservecalifornia/
│   └── slack/
├── config/
├── db/
├── model/               ← rename from models/, flatten
│   ├── api/             ← request/response DTOs
│   ├── domain/          ← core domain types
│   └── wire/            ← vendor-specific wire formats
├── repo/                ← stays flat
├── route/               ← rename from routes/
│   ├── availability/
│   ├── pois/
│   ├── admin/
│   ├── geocode/
│   ├── health/
│   ├── route/
│   ├── slack/
│   └── static/
├── service/             ← flatten adapter nesting
│   ├── availability/    ← providers live here directly (no adapters/ subdir)
│   ├── booking/         ← same: adapters flattened
│   ├── etl/
│   ├── notification/
│   ├── poi/
│   ├── ratelimit/
│   ├── routing/
│   └── scheduler/
└── support/             ← NEW: Dispatchable, shared utilities, exceptions, http helpers
```

Key moves:
- `service/availability/provider/adapters/recgov/` → `service/availability/RecGovProvider.kt`
- `service/availability/provider/adapters/aspira/` → `service/availability/AspiraProvider.kt`
- `service/booking/adapters/recgov/` → `service/booking/RecGovBookingProvider.kt`
- `models/` → `model/` with three clear buckets (api, domain, wire)
- `exceptions/`, `http/` → `support/`
- `SlackInteractivityWiring.kt` → `service/notification/slack/`
- `Dispatchable.kt` → `support/`

## Commit Sequence

Single PR, incremental commits:

1. **Add Koin dependencies** — `koin-ktor`, `koin-core`, `koin-test` in build.gradle.kts; install Koin plugin in `Application.module()`
2. **Dispatchable convention** — add `Dispatchable<K>` interface and extension functions; typed keys (`TriggerKind` enum, `AlertProviderId` enum, `AvailabilitySourceId` value class)
3. **infraModule** — config, DataSource, DSLContext, HTTP clients, CoroutineScope, PoiRegistry
4. **repoModule** — all repo classes
5. **serviceModule** — services + `List<T>` multi-bindings; add `canHandle()` to provider interfaces
6. **routeModule** — route classes with `by inject()`; `CampgroundAvailabilitySupport` absorbed
7. **Delete old wiring** — `RoadtripBootContext`, `RoadtripRuntime`, `startRoadtripRuntime()`, `createRoadtripBootContext()`, all `*Registry` classes, `AvailabilityProviderClients`, `registerRoadtripRoutes()`
8. **Migrate tests** — convert to `KoinTest`, add `checkModules()` verification
9. **Folder restructure** — purely `git mv` operations, update package declarations and imports

## What Gets Deleted

- `RoadtripBootContext.kt`
- `RoadtripRuntime.kt` (including `startRoadtripRuntime()`)
- `AvailabilityProviderRegistry.kt`
- `BookingProviderRegistry.kt`
- `AlertProviderRegistry.kt`
- `TriggerActionRegistry.kt`
- `AvailabilityProviderClients.kt`
- `registerRoadtripRoutes()` function
- Manual `monitor.subscribe(ApplicationStopping)` shutdown logic

## Dependencies Added

- `io.insert-koin:koin-ktor:4.x`
- `io.insert-koin:koin-core:4.x`
- `io.insert-koin:koin-test:4.x`
- `io.insert-koin:koin-test-junit5:4.x` (if using JUnit 5)
