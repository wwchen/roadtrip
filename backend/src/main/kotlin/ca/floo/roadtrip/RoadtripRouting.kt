package ca.floo.roadtrip

import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.http.cacheOptionsFor
import ca.floo.roadtrip.routes.api.pois.IpRateLimiter
import ca.floo.roadtrip.routes.api.roadtripApiRoutes
import ca.floo.roadtrip.routes.static.staticSiteRoutes
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityService
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import ca.floo.roadtrip.service.poi.PoiReader
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
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
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import org.jooq.DSLContext
import org.koin.ktor.ext.getKoin
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
    val koin = getKoin()
    val appConfig = koin.get<AppConfig>()
    val staticDir = koin.get<File>()
    val slackInteractivity =
        if (appConfig.slack?.signingSecret == null) {
            null
        } else {
            koin.get<SlackInteractivityWiring>()
        }
    val ingestController = koin.get<IngestController>()
    val ctx = koin.get<DSLContext>()
    val availabilityWatchService = koin.get<AvailabilityWatchService>()
    val watchAlertDispatcher = koin.get<WatchAlertDispatcher>()
    val schedulerScope = koin.get<CoroutineScope>()
    val watchCapabilities = koin.get<WatchCapabilityService>()
    val campsiteCatalogService = koin.get<CampsiteCatalogService>()
    val campsiteAvailabilityService = koin.get<CampsiteAvailabilityService>()
    val campsiteRateLimiter = koin.get<IpRateLimiter>()
    val routeCache = koin.get<RouteCache>()
    val mapboxGeocoder = koin.get<MapboxGeocoder>()
    val poiService = koin.get<PoiReader>()
    val routeCorridorService = koin.get<RouteCorridorService>()
    val poisOnRouteService = koin.get<PoisOnRouteService>()
    val emailNotifications = koin.get<EmailNotificationService>()
    val slackNotifications = koin.get<SlackNotificationService>()
    routing {
        roadtripApiRoutes(
            appConfig = appConfig,
            slackInteractivity = slackInteractivity,
            ingestController = ingestController,
            ctx = ctx,
            availabilityWatchService = availabilityWatchService,
            watchAlertDispatcher = watchAlertDispatcher,
            schedulerScope = schedulerScope,
            watchCapabilities = watchCapabilities,
            campsiteCatalogService = campsiteCatalogService,
            campsiteAvailabilityService = campsiteAvailabilityService,
            campsiteRateLimiter = campsiteRateLimiter,
            routeCache = routeCache,
            mapboxGeocoder = mapboxGeocoder,
            poiService = poiService,
            routeCorridorService = routeCorridorService,
            poisOnRouteService = poisOnRouteService,
            emailNotifications = emailNotifications,
            slackNotifications = slackNotifications,
        )
        staticSiteRoutes(staticDir)
    }
}
