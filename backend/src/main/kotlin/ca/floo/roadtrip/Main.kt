package ca.floo.roadtrip

import ca.floo.roadtrip.clients.aspira.HttpAspiraAvailabilityClient
import ca.floo.roadtrip.clients.cache.RouteCache
import ca.floo.roadtrip.clients.mapbox.MapboxDirections
import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.clients.recgov.HttpRecgovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.HttpReserveAmericaAvailabilityClient
import ca.floo.roadtrip.clients.reservecalifornia.HttpReserveCaliforniaAvailabilityClient
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.http.cacheOptionsFor
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.ApiCacheRepo
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.DbConfig
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.dataSourceFor
import ca.floo.roadtrip.repo.dsl
import ca.floo.roadtrip.repo.migrate
import ca.floo.roadtrip.routes.adminIngestRoutes
import ca.floo.roadtrip.routes.availabilityDashboardRoutes
import ca.floo.roadtrip.routes.availabilityRoutes
import ca.floo.roadtrip.routes.availabilityWatchRoutes
import ca.floo.roadtrip.routes.geocodeRoutes
import ca.floo.roadtrip.routes.healthRoutes
import ca.floo.roadtrip.routes.poiRoutes
import ca.floo.roadtrip.routes.poisOnRouteRoutes
import ca.floo.roadtrip.routes.reservableRoutes
import ca.floo.roadtrip.routes.routeRoutes
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.AvailabilityServiceImpl
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.CatalogAvailabilityBatcher
import ca.floo.roadtrip.service.availability.CoordinateTimeZones
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.ReservableAvailabilityComposer
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.etl.framework.fetchTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.framework.sweepStaleIngestRuns
import ca.floo.roadtrip.service.notification.SlackNotificationServiceImpl
import ca.floo.roadtrip.service.ratelimit.VendorRateLimitConfig
import ca.floo.roadtrip.service.ratelimit.VendorRateLimiter
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.ReservationProviderClients
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistryFactory
import ca.floo.roadtrip.service.scheduler.PollerBackfill
import ca.floo.roadtrip.service.scheduler.WatchReaper
import ca.floo.roadtrip.service.scheduler.framework.Scheduler
import ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutor
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

internal fun includeInRoadtripOpenApi(path: List<String>): Boolean = path.firstOrNull() == "api" && path.getOrNull(1) != "docs"

fun Application.module() {
    val appConfig = AppConfig.fromEnv()
    val ds = dataSourceFor(DbConfig.fromEnv())
    // Run Flyway in-process so the app can boot against either a hand-
    // bootstrapped DB or a fresh database.
    migrate(ds)
    val ctx = dsl(ds)
    val reservationProviderClients =
        ReservationProviderClients(
            recgovClient = HttpRecgovAvailabilityClient(),
            aspiraClient = HttpAspiraAvailabilityClient(),
            reserveAmericaClient = HttpReserveAmericaAvailabilityClient(),
            reserveCaliforniaClient = HttpReserveCaliforniaAvailabilityClient(),
        )

    // ROADTRIP_STATIC_DIR points at the repo checkout when running locally
    // (gradle run) or at /app/static inside the container (bind-mounted from
    // the host's repo root). Raw upstream captures live under data/raw/.
    val staticDir = File(System.getenv("ROADTRIP_STATIC_DIR") ?: ".")

    // Mapbox Directions for /api/route + Mapbox Geocoding for /api/geocode.
    // Both share MAPBOX_TOKEN. Token stays server-side — never sent to the
    // browser. Endpoints respond 503 if unset; the rest of the app is
    // unaffected.
    val mapboxToken = System.getenv("MAPBOX_TOKEN")
    val mapboxGeocoder = MapboxGeocoder(token = mapboxToken)
    // /api/route seeds the cache; /api/pois/on-route reads it for corridor
    // filtering so the FE doesn't have to ship a turf.buffer polygon over
    // the wire. See RouteCache.kt.
    val routeCache =
        RouteCache(
            MapboxDirections(token = mapboxToken),
            ttl = appConfig.cache.ttlFor(ApiCacheEntity.ROUTE),
            persistentCache = ApiCacheRepo(ctx),
        )

    // POI registry — config/poi-registry.yaml is the source of truth for
    // the per-data_source fetch recipes and the per-poi_data ETL chains.
    // Validates + topo-sorts the DAG at boot; refuses to start on duplicate
    // slugs, dangling inputs, forward references, or cycles.
    val poiRegistry = PoiRegistry.load(File(staticDir, "config/poi-registry.yaml"))

    // Ingestion controller (RFC 0004 / issue #44) — observability + remote
    // trigger layer around the data-fetch (Python scripts) + data-import
    // (Kotlin Importer) phases. Boot recovery first, so admins see a clean
    // dashboard.
    sweepStaleIngestRuns(ctx)
    val ingestController =
        IngestController(
            ctx = ctx,
            etl =
                EtlOrchestrator(
                    ctx,
                    File(staticDir, "data/raw"),
                    poiRegistry,
                ),
            fetchTargets = fetchTargetsFromRegistry(poiRegistry, staticDir),
            importTargets = importTargetsFromRegistry(poiRegistry),
            workingDir = staticDir,
        )

    // Self-documenting /api/docs (issue #47). Builds the OpenAPI spec from
    // the live routing tree at boot; routes carry their own `documentation
    // { summary = ... }` blocks. /api/docs serves Swagger UI; /api/docs/openapi.json
    // serves the spec. Both are public — non-sensitive paths + summaries.
    install(SwaggerUI) {
        pathFilter = { _, path -> includeInRoadtripOpenApi(path) }
        info {
            title = "roadtrip API"
            description = "Backend for roadtrip.floo.ca. Endpoints reflect the live routing tree."
        }
    }

    install(ConditionalHeaders)
    install(Compression) {
        gzip {
            // geojson is the big payoff (~5x smaller). Don't gzip below 1KB —
            // header overhead outweighs savings.
            minimumSize(1024)
            matchContentType(
                ContentType.Text.Html,
                ContentType.Text.CSS,
                ContentType.Application.Json,
                ContentType.Application.JavaScript,
                ContentType("application", "geo+json"),
                ContentType.Image.SVG,
            )
        }
    }
    install(CachingHeaders) {
        options { call, content ->
            cacheOptionsFor(call.request.path(), content.contentType)
        }
    }

    // Reservation-provider port registry: one adapter per upstream reservation
    // system, dispatched by `pois.source`. Main owns outbound HTTP client
    // construction; the factory owns registry-source -> adapter mapping. Routes
    // consume the registry and never see vendor types. See docs/reservation-providers.md.
    val reservationProviderRegistry =
        ReservationProviderRegistryFactory.build(
            registry = poiRegistry,
            clients = reservationProviderClients,
        )

    val reservablesRepo = ReservableRepo(ctx)
    val campsiteProviders = CampsiteProviderRepo(ctx)
    val availability = AvailabilityRepo(ctx)
    val availabilityDateResolver = AvailabilityDateResolver()
    CoordinateTimeZones.warmUp()
    val availabilityTargets =
        DbAvailabilityTargetResolver(
            providerRefs = campsiteProviders,
            reservablesRepo = reservablesRepo,
            reservationProviders = reservationProviderRegistry,
            dateResolver = availabilityDateResolver,
        )
    val availabilityService =
        AvailabilityServiceImpl(
            providerRefs = campsiteProviders,
            reservablesRepo = reservablesRepo,
            composer =
                ReservableAvailabilityComposer(
                    targets = availabilityTargets,
                    dateResolver = availabilityDateResolver,
                    availability = availability,
                    snapshotFreshnessTtl = { providerId ->
                        appConfig.cache.ttlFor(
                            when (providerId) {
                                ReservationProviderId.RECGOV -> ApiCacheEntity.RECGOV_AVAILABILITY
                                ReservationProviderId.ASPIRA -> ApiCacheEntity.ASPIRA_AVAILABILITY
                                ReservationProviderId.RESERVEAMERICA -> ApiCacheEntity.RESERVEAMERICA_AVAILABILITY
                                ReservationProviderId.RESERVECALIFORNIA -> ApiCacheEntity.RESERVECALIFORNIA_AVAILABILITY
                            },
                        )
                    },
                ),
            dateResolver = availabilityDateResolver,
            reservationProviders = reservationProviderRegistry,
        )
    val availabilityWatchService = AvailabilityWatchService(ctx, reservablesRepo, availabilityTargets)

    // Availability poller. One Scheduler<Poller> ticks every few seconds,
    // claims due pollers, calls AvailabilityPollExecutor (which derives the
    // window/cadence from each poller's live watches), and writes snapshot
    // rows. Cancelled on app shutdown so tests don't leak threads.
    val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val availabilityPollers = AvailabilityPollerRepo(ctx)
    // Boot backfill: link any active watch left without poller membership by
    // the V28 cutover. Idempotent — a no-op once everything is linked. Runs
    // after Flyway (migrate(ds), above) and before the scheduler starts.
    PollerBackfill(ctx, AvailabilityPollerMembership(WatchScopeResolver(reservablesRepo), availabilityTargets)).run()
    // Durable, Postgres-backed per-vendor fetch governor. Per-vendor bucket
    // capacity/refill come from the env (ROADTRIP_VENDOR_RATELIMIT_*), defaulting
    // to a conservative code-level bucket for unconfigured vendors.
    val slackNotifications = SlackNotificationServiceImpl(appConfig.slack)
    val watchAlertDispatcher =
        WatchAlertDispatcher(
            slack = slackNotifications,
            scopeResolver = WatchScopeResolver(reservablesRepo),
            watches = AvailabilityWatchRepo(ctx),
            targets = availabilityTargets,
            pois = PoiServingRepo(ctx),
            availability = availability,
            grafanaRootUrl = appConfig.grafana?.rootUrl,
            appRootUrl = appConfig.webApp?.rootUrl,
        )
    Scheduler(
        repo = availabilityPollers,
        handler =
            AvailabilityPollExecutor(
                pollers = availabilityPollers,
                reservablesRepo = reservablesRepo,
                batcher = CatalogAvailabilityBatcher(),
                availability = availability,
                runs = AvailabilityRunRepo(ctx),
                dateResolver = availabilityDateResolver,
                targets = availabilityTargets,
                fetchCalls = AvailabilityFetchCallRepo(ctx),
                limiter = VendorRateLimiter(VendorRateLimitConfig.fromEnv(), ds),
                alertDispatcher = watchAlertDispatcher,
            )::handle,
        name = "availability",
    ).start(schedulerScope)
    // Elapsed-watch teardown runs on its own cadence, not on the poll tick:
    // marks elapsed watches done, drops their poller links, deactivates
    // orphaned pollers. Reads are correct without it (liveness is derived); it
    // just materializes the lifecycle transitions.
    WatchReaper(availabilityPollers).start(schedulerScope)
    environment.monitor.subscribe(ApplicationStopping) {
        schedulerScope.cancel()
        reservationProviderClients.close()
        slackNotifications.close()
    }

    routing {
        // /api/docs — Swagger UI; /api/docs/openapi.json — the spec it loads.
        // Both must be mounted before the static file fallthrough at "/" so
        // the catch-all doesn't shadow them.
        route("/api/docs") {
            swaggerUI("/api/docs/openapi.json")
        }
        route("/api/docs/openapi.json") {
            openApiSpec()
        }

        poiRoutes(
            ctx = ctx,
            registry = poiRegistry,
            dateResolver = availabilityDateResolver,
            availabilitySupport = { row ->
                val providerRefJson = row.providerRefJson
                providerRefJson != null &&
                    ProviderRefParser.parse(providerRefJson) != null &&
                    reservationProviderRegistry
                        .forSource(row.source)
                        ?.capabilities
                        ?.supportsAvailability == true
            },
        )
        reservableRoutes(ctx)
        availabilityWatchRoutes(ctx, availabilityWatchService, watchAlertDispatcher, schedulerScope)
        availabilityDashboardRoutes(ctx)
        poisOnRouteRoutes(ctx, routeCache, poiRegistry)
        routeRoutes(routeCache, ctx)
        geocodeRoutes(mapboxGeocoder)
        healthRoutes()
        availabilityRoutes(
            routeService = availabilityService,
        )
        adminIngestRoutes(ingestController, ctx)
        // Static site. /web/* and /data/* serve directly from the repo
        // checkout. Root path serves index.html. data/raw/ stays
        // server-private — it's the upstream capture cache, never served.
        staticFiles("/web", File(staticDir, "web"))
        staticFiles("/data", File(staticDir, "data")) {
            exclude { it.path.contains("/raw/") }
            contentType { f ->
                if (f.name.endsWith(".geojson")) ContentType("application", "geo+json") else null
            }
        }
        get("/availability") {
            call.respondFile(File(staticDir, "availability.html"))
        }
        get("/availability/") {
            call.respondFile(File(staticDir, "availability.html"))
        }
        staticFiles("/", staticDir) {
            default("index.html")
            // Only serve top-level files (index.html, favicon.ico, etc.) from
            // the root mount — /web and /data have their own routes above,
            // and we don't want to expose backend/, scripts/, etc.
            exclude { f ->
                val rel = f.relativeTo(staticDir).path
                rel.contains(File.separator) // disallow nested paths
            }
        }
    }
}
