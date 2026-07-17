package ca.floo.roadtrip.routes.api

import ca.floo.roadtrip.SlackInteractivityWiring
import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.routes.api.admin.adminIngestRoutes
import ca.floo.roadtrip.routes.api.availability.availabilityDashboardRoutes
import ca.floo.roadtrip.routes.api.availability.availabilityWatchRoutes
import ca.floo.roadtrip.routes.api.docs.apiDocsRoutes
import ca.floo.roadtrip.routes.api.geocode.geocodeRoutes
import ca.floo.roadtrip.routes.api.health.healthRoutes
import ca.floo.roadtrip.routes.api.pois.IpRateLimiter
import ca.floo.roadtrip.routes.api.pois.campsiteRoutes
import ca.floo.roadtrip.routes.api.pois.poiRoutes
import ca.floo.roadtrip.routes.api.pois.poisOnRouteRoutes
import ca.floo.roadtrip.routes.api.route.routeRoutes
import ca.floo.roadtrip.routes.api.slack.slackInteractivityRoute
import ca.floo.roadtrip.routes.test.testEmailRoutes
import ca.floo.roadtrip.routes.test.testSlackRoutes
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
import io.ktor.server.routing.Route
import kotlinx.coroutines.CoroutineScope
import org.jooq.DSLContext

internal fun Route.roadtripApiRoutes(
    appConfig: AppConfig,
    slackInteractivity: SlackInteractivityWiring?,
    ingestController: IngestController,
    ctx: DSLContext,
    availabilityWatchService: AvailabilityWatchService,
    watchAlertDispatcher: WatchAlertDispatcher,
    schedulerScope: CoroutineScope,
    watchCapabilities: WatchCapabilityService,
    campsiteCatalogService: CampsiteCatalogService,
    campsiteAvailabilityService: CampsiteAvailabilityService,
    campsiteRateLimiter: IpRateLimiter,
    routeCache: RouteCache,
    mapboxGeocoder: MapboxGeocoder,
    poiService: PoiReader,
    routeCorridorService: RouteCorridorService,
    poisOnRouteService: PoisOnRouteService,
    emailNotifications: EmailNotificationService,
    slackNotifications: SlackNotificationService,
) {
    apiDocsRoutes()
    poiRoutes(poiService)
    availabilityWatchRoutes(
        ctx,
        availabilityWatchService,
        watchAlertDispatcher,
        schedulerScope,
        watchCapabilities,
    )
    campsiteRoutes(
        catalogService = campsiteCatalogService,
        availabilityService = campsiteAvailabilityService,
        rateLimit = campsiteRateLimiter,
    )
    slackInteractivity?.let { wiring ->
        slackInteractivityRoute(wiring.verifier, wiring.handler, schedulerScope)
    }
    availabilityDashboardRoutes(
        ctx = ctx,
        forcePullCooldown = appConfig.availability.forcePullCooldown,
    )
    poisOnRouteRoutes(poisOnRouteService)
    routeRoutes(routeCache, routeCorridorService)
    geocodeRoutes(mapboxGeocoder)
    healthRoutes()
    adminIngestRoutes(ingestController, ctx)
    testEmailRoutes(emailNotifications)
    testSlackRoutes(slackNotifications)
}
