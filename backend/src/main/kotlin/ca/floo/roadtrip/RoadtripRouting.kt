package ca.floo.roadtrip

import ca.floo.roadtrip.http.cacheOptionsFor
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
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
import ca.floo.roadtrip.service.api.PoiService
import ca.floo.roadtrip.service.api.PoisOnRouteService
import ca.floo.roadtrip.service.availability.PoiAvailabilitySupport
import ca.floo.roadtrip.service.catalog.CampgroundService
import ca.floo.roadtrip.service.catalog.PlanetFitnessLocationService
import ca.floo.roadtrip.service.catalog.TeslaSuperchargerService
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
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
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

internal fun Application.registerRoadtripRoutes(runtime: RoadtripRuntime) {
    val poiAvailabilitySupport =
        PoiAvailabilitySupport(
            providerRefs = runtime.campsiteProviders,
            availabilityProviders = runtime.availabilityProviderRegistry,
        )
    val poiService =
        PoiService(
            poiRepo = PoiServingRepo(runtime.ctx),
            campgroundService = CampgroundService(CampgroundRepo(runtime.ctx)),
            teslaSuperchargerService = TeslaSuperchargerService(TeslaSuperchargerRepo(runtime.ctx)),
            planetFitnessLocationService = PlanetFitnessLocationService(PlanetFitnessLocationRepo(runtime.ctx)),
            dateResolver = runtime.availabilityDateResolver,
            availabilitySupport = poiAvailabilitySupport::supports,
            availabilityProvider = { row -> poiAvailabilitySupport.preferredAvailabilityProvider(row.id) },
        )
    val routeCorridorService = RouteCorridorService(RouteCorridorRepo(runtime.ctx))
    val poisOnRouteService =
        PoisOnRouteService(
            routeCache = runtime.routeCache,
            routeCorridorService = routeCorridorService,
            poiService = poiService,
        )
    routing {
        route("/api/docs") {
            swaggerUI("/api/docs/openapi.json")
        }
        route("/api/docs/openapi.json") {
            openApiSpec()
        }

        poiRoutes(poiService)
        availabilityWatchRoutes(
            runtime.ctx,
            runtime.availabilityWatchService,
            runtime.watchAlertDispatcher,
            runtime.schedulerScope,
        )
        campsiteRoutes(
            ctx = runtime.ctx,
            availabilityProviders = runtime.availabilityProviderRegistry,
            dateResolver = runtime.availabilityDateResolver,
            failoverFetcher = runtime.failoverFetcher,
        )
        // Inbound Slack interactivity is only registered when the app is
        // configured with a signing secret; an unset secret means we can't
        // verify anything, so leave the route absent (404) rather than
        // answering 401 to every probe.
        runtime.slackInteractivity?.let { wiring ->
            slackInteractivityRoute(wiring.verifier, wiring.handler, runtime.schedulerScope)
        }
        availabilityDashboardRoutes(runtime.ctx)
        poisOnRouteRoutes(poisOnRouteService)
        routeRoutes(runtime.routeCache, routeCorridorService)
        geocodeRoutes(runtime.mapboxGeocoder)
        healthRoutes()
        staticSiteRoutes(runtime.staticDir)
    }
}

internal fun Application.installAdminPlugins() {
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status = call.response.status()?.value ?: UNKNOWN_HTTP_STATUS
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val durationMs = call.processingTimeMillis()
            val remote = call.request.local.remoteHost
            "$API_ACCESS_LOG_PREFIX method=$method path=$path status=$status duration_ms=$durationMs remote=$remote"
        }
    }
}

internal fun Application.registerAdminRoutes(boot: RoadtripBootContext) {
    routing {
        adminIngestRoutes(boot.ingestController, boot.ctx)
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
