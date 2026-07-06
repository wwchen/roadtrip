package ca.floo.roadtrip

import ca.floo.roadtrip.http.cacheOptionsFor
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
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
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
import java.io.File

internal fun Application.installRoadtripPlugins() {
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
    routing {
        route("/api/docs") {
            swaggerUI("/api/docs/openapi.json")
        }
        route("/api/docs/openapi.json") {
            openApiSpec()
        }

        poiRoutes(
            ctx = runtime.ctx,
            registry = runtime.poiRegistry,
            dateResolver = runtime.availabilityDateResolver,
            availabilitySupport = { row ->
                val providerRefJson = row.providerRefJson
                providerRefJson != null &&
                    ProviderRefParser.parse(providerRefJson) != null &&
                    runtime.reservationProviderRegistry
                        .forSource(row.source)
                        ?.capabilities
                        ?.supportsAvailability == true
            },
        )
        reservableRoutes(runtime.ctx)
        availabilityWatchRoutes(
            runtime.ctx,
            runtime.availabilityWatchService,
            runtime.watchAlertDispatcher,
            runtime.schedulerScope,
        )
        availabilityDashboardRoutes(runtime.ctx)
        poisOnRouteRoutes(runtime.ctx, runtime.routeCache, runtime.poiRegistry)
        routeRoutes(runtime.routeCache, runtime.ctx)
        geocodeRoutes(runtime.mapboxGeocoder)
        healthRoutes()
        availabilityRoutes(routeService = runtime.availabilityService)
        adminIngestRoutes(runtime.ingestController, runtime.ctx)
        staticSiteRoutes(runtime.staticDir)
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
