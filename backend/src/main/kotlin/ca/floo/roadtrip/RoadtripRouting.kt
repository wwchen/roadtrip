package ca.floo.roadtrip

import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.http.cacheOptionsFor
import ca.floo.roadtrip.repo.AdminIngestReadRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.routes.IpRateLimiter
import ca.floo.roadtrip.routes.adminIngestRoutes
import ca.floo.roadtrip.routes.availabilityDashboardRoutes
import ca.floo.roadtrip.routes.availabilityWatchRoutes
import ca.floo.roadtrip.routes.campsiteRoutes
import ca.floo.roadtrip.routes.geocodeRoutes
import ca.floo.roadtrip.routes.healthRoutes
import ca.floo.roadtrip.routes.poiRoutes
import ca.floo.roadtrip.routes.poisOnRouteRoutes
import ca.floo.roadtrip.routes.routeRoutes
import ca.floo.roadtrip.routes.slackInteractivityRoute
import ca.floo.roadtrip.routes.testEmailRoutes
import ca.floo.roadtrip.routes.testSlackRoutes
import ca.floo.roadtrip.service.availability.AvailabilityWatchApiMapper
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import ca.floo.roadtrip.service.poi.PoiReader
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import org.slf4j.event.Level
import java.io.File

private const val API_ACCESS_LOG_PREFIX = "http_api request"
private const val API_HEALTH_PATH = "/api/health"
private const val API_PATH_PREFIX = "/api/"
private const val UNKNOWN_HTTP_STATUS = 0

internal fun Application.installRoadtripPlugins() {
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            path.startsWith(API_PATH_PREFIX) && path != API_HEALTH_PATH
        }
        format { call ->
            val status = call.response.status()?.value ?: UNKNOWN_HTTP_STATUS
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val durationMs = call.processingTimeMillis()
            val remote = call.request.local.remoteHost
            "$API_ACCESS_LOG_PREFIX method=$method path=$path status=$status duration_ms=$durationMs remote=$remote"
        }
    }
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
}

internal fun Application.registerRoadtripRoutes() {
    val appConfig: AppConfig by dependencies
    val staticDir: File by dependencies
    val slackInteractivity: SlackInteractivityWiring? by dependencies
    val ingestController: IngestController by dependencies
    val adminIngestReadRepo: AdminIngestReadRepo by dependencies
    val availabilityPollers: AvailabilityPollerRepo by dependencies
    val availabilityRuns: AvailabilityRunRepo by dependencies
    val availability: AvailabilityRepo by dependencies
    val availabilityWatches: AvailabilityWatchRepo by dependencies
    val campsitesRepo: CampsiteRepo by dependencies
    val availabilityWatchMapper: AvailabilityWatchApiMapper by dependencies
    val availabilityWatchService: AvailabilityWatchService by dependencies
    val watchAlertDispatcher: WatchAlertDispatcher by dependencies
    val schedulerScope: CoroutineScope by dependencies
    val routeCache: RouteCache by dependencies
    val mapboxGeocoder: MapboxGeocoder by dependencies
    val poiService: PoiReader by dependencies
    val routeCorridorService: RouteCorridorService by dependencies
    val poisOnRouteService: PoisOnRouteService by dependencies
    val campsiteCatalogService: CampsiteCatalogService by dependencies
    val campsiteAvailabilityService: CampsiteAvailabilityService by dependencies
    val campsiteRateLimiter: IpRateLimiter by dependencies
    val emailNotifications: EmailNotificationService by dependencies
    val slackNotifications: SlackNotificationService by dependencies
    routing {
        route("/api/docs") {
            swaggerUI("/api/docs/openapi.json")
        }
        route("/api/docs/openapi.json") {
            openApiSpec()
        }

        poiRoutes(poiService)
        availabilityWatchRoutes(
            watches = availabilityWatches,
            watchMapper = availabilityWatchMapper,
            watchService = availabilityWatchService,
            alertDispatcher = watchAlertDispatcher,
            notifyScope = schedulerScope,
        )
        campsiteRoutes(
            catalogService = campsiteCatalogService,
            availabilityService = campsiteAvailabilityService,
            rateLimit = campsiteRateLimiter,
        )
        // Inbound Slack interactivity is only registered when the app is
        // configured with a signing secret; an unset secret means we can't
        // verify anything, so leave the route absent (404) rather than
        // answering 401 to every probe.
        slackInteractivity?.let { wiring ->
            slackInteractivityRoute(wiring.verifier, wiring.handler, schedulerScope)
        }
        availabilityDashboardRoutes(
            pollers = availabilityPollers,
            runs = availabilityRuns,
            availability = availability,
            campsitesRepo = campsitesRepo,
            forcePullCooldown = appConfig.availability.forcePullCooldown,
        )
        poisOnRouteRoutes(poisOnRouteService)
        routeRoutes(routeCache, routeCorridorService)
        geocodeRoutes(mapboxGeocoder)
        healthRoutes()
        adminIngestRoutes(ingestController, adminIngestReadRepo)
        testEmailRoutes(emailNotifications)
        testSlackRoutes(slackNotifications)
        staticSiteRoutes(staticDir)
    }
}

private fun io.ktor.server.routing.Route.staticSiteRoutes(staticDir: File) {
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
        exclude { f ->
            val rel = f.relativeTo(staticDir).path
            rel.contains(File.separator)
        }
    }
}
