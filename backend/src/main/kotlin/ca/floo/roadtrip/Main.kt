package ca.floo.roadtrip

import ca.floo.roadtrip.client.AspiraAvailabilityClient
import ca.floo.roadtrip.client.MapboxDirections
import ca.floo.roadtrip.client.MapboxGeocoder
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.http.cacheOptionsFor
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.ApiCacheRepo
import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.DbConfig
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.RouteCache
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
import ca.floo.roadtrip.service.api.ReservableAvailabilityFetchService
import ca.floo.roadtrip.service.api.recgov.AvailabilityClient
import ca.floo.roadtrip.service.api.recgov.CachedAvailability
import ca.floo.roadtrip.service.availability.AvailabilityPollExecutor
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.etl.EtlOrchestrator
import ca.floo.roadtrip.service.etl.IngestController
import ca.floo.roadtrip.service.etl.fetchTargetsFromRegistry
import ca.floo.roadtrip.service.etl.importTargetsFromRegistry
import ca.floo.roadtrip.service.etl.sweepStaleIngestRuns
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistryFactory
import ca.floo.roadtrip.service.scheduler.Scheduler
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

fun Application.module() {
    val appConfig = AppConfig.fromEnv()
    val ds = dataSourceFor(DbConfig.fromEnv())
    // Run Flyway in-process so the app can boot against either a hand-
    // bootstrapped DB or a fresh database.
    migrate(ds)
    val ctx = dsl(ds)
    val persistentCache = ApiCacheRepo(ctx)
    val recgovAvailabilityClient = AvailabilityClient()
    val recgovAvailabilityCache =
        CachedAvailability(
            recgovAvailabilityClient,
            ttl = appConfig.cache.ttlFor(ApiCacheEntity.RECGOV_AVAILABILITY),
            persistentCache = persistentCache,
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
    val mapboxDirections = MapboxDirections(token = mapboxToken)
    val mapboxGeocoder = MapboxGeocoder(token = mapboxToken)
    // /api/route seeds the cache; /api/pois/on-route reads it for corridor
    // filtering so the FE doesn't have to ship a turf.buffer polygon over
    // the wire. See RouteCache.kt.
    val routeCache =
        RouteCache(
            mapboxDirections,
            ttl = appConfig.cache.ttlFor(ApiCacheEntity.ROUTE),
            persistentCache = persistentCache,
        )

    // POI registry — config/poi-registry.yaml is the source of truth for
    // the per-data_source fetch recipes and the per-poi_data ETL chains.
    // Validates + topo-sorts the DAG at boot; refuses to start on duplicate
    // slugs, dangling inputs, forward references, or cycles.
    val registryFile = File(staticDir, "config/poi-registry.yaml")
    val poiRegistry = PoiRegistry.load(registryFile)

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

    // Aspira NextGen availability (Parks Canada / WA State / BC Discover Camping).
    // Same pattern as rec.gov's CachedAvailability — process-wide singleton
    // with config-driven TTL and a 1.5s mutex against Aspira's Azure WAF.
    // See ca/floo/roadtrip/aspira/AspiraAvailabilityClient.kt.
    val aspiraClient = AspiraAvailabilityClient()
    val aspiraCache =
        CachedAspiraAvailability(
            aspiraClient,
            ttl = appConfig.cache.ttlFor(ApiCacheEntity.ASPIRA_AVAILABILITY),
            persistentCache = persistentCache,
        )
    // Reservation-provider port registry: one adapter per upstream reservation
    // system, dispatched by `pois.source`. Routes consume the registry; they
    // never see vendor types. See docs/reservation-providers.md.
    val reservationProviderRegistry =
        ReservationProviderRegistryFactory.build(
            registry = poiRegistry,
            recgovCache = recgovAvailabilityCache,
            aspiraCache = aspiraCache,
        )

    val availabilityWatchService = AvailabilityWatchService(ctx, ReservableRepo(ctx))

    // Availability poller. One Scheduler<AvailabilityJob> ticks every few
    // seconds, claims due jobs, calls AvailabilityPollExecutor, and writes
    // snapshot rows. Cancelled on app shutdown so tests don't leak threads.
    val schedulerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val availabilityJobs = AvailabilityJobRepo(ctx)
    val availabilityFetches =
        ReservableAvailabilityFetchService(
            snapshots = AvailabilitySnapshotRepo(ctx),
        )
    val pollExecutor =
        AvailabilityPollExecutor(
            reservables =
                ca.floo.roadtrip.repo
                    .ReservableRepo(ctx),
            campsiteProviders = CampsiteProviderRepo(ctx),
            reservationProviders = reservationProviderRegistry,
            fetches = availabilityFetches,
            runs = AvailabilityJobRunRepo(ctx),
        )
    val availabilityScheduler =
        Scheduler(
            repo = availabilityJobs,
            handler = pollExecutor::handle,
            name = "availability",
        )
    availabilityScheduler.start(schedulerScope)
    environment.monitor.subscribe(ApplicationStopping) {
        schedulerScope.cancel()
        recgovAvailabilityClient.close()
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

        poiRoutes(ctx, poiRegistry)
        reservableRoutes(ctx)
        availabilityWatchRoutes(ctx, availabilityWatchService)
        availabilityDashboardRoutes(ctx)
        poisOnRouteRoutes(ctx, routeCache, poiRegistry)
        routeRoutes(routeCache, ctx)
        geocodeRoutes(mapboxGeocoder)
        healthRoutes()
        availabilityRoutes(
            CampsiteProviderRepo(ctx),
            reservationProviderRegistry,
            ReservableRepo(ctx),
            AvailabilitySnapshotRepo(ctx),
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
        get("/pois") {
            call.respondFile(File(staticDir, "pois.html"))
        }
        get("/pois/") {
            call.respondFile(File(staticDir, "pois.html"))
        }
        get("/reservables") {
            call.respondFile(File(staticDir, "reservables.html"))
        }
        get("/reservables/") {
            call.respondFile(File(staticDir, "reservables.html"))
        }
        get("/watches") {
            call.respondFile(File(staticDir, "watches.html"))
        }
        get("/watches/") {
            call.respondFile(File(staticDir, "watches.html"))
        }
        get("/watches/{id}") {
            call.respondFile(File(staticDir, "watch-detail.html"))
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
