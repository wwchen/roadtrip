package ca.floo.roadtrip.di

import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.route.api.admin.adminIngestRoutes
import ca.floo.roadtrip.route.api.availability.availabilityDashboardRoutes
import ca.floo.roadtrip.route.api.availability.availabilityWatchRoutes
import ca.floo.roadtrip.route.api.docs.apiDocsRoutes
import ca.floo.roadtrip.route.api.geocode.geocodeRoutes
import ca.floo.roadtrip.route.api.health.healthRoutes
import ca.floo.roadtrip.route.api.pois.campsiteRoutes
import ca.floo.roadtrip.route.api.pois.poiRoutes
import ca.floo.roadtrip.route.api.pois.poisOnRouteRoutes
import ca.floo.roadtrip.route.api.route.routeRoutes
import ca.floo.roadtrip.route.api.slack.slackInteractivityRoute
import ca.floo.roadtrip.route.static.staticSiteRoutes
import ca.floo.roadtrip.route.test.testEmailRoutes
import ca.floo.roadtrip.route.test.testSlackRoutes
import ca.floo.roadtrip.service.availability.AvailabilityDashboardController
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityWatchApiMapper
import ca.floo.roadtrip.service.availability.AvailabilityWatchController
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.notification.slack.SlackNotificationService
import ca.floo.roadtrip.service.poi.PoiReader
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import org.jooq.DSLContext
import org.koin.core.qualifier.named
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import java.io.File
import java.time.Duration

internal fun Application.registerKoinRoutes() {
    val ctx: DSLContext by inject()
    val config: AppConfig by inject()
    val watchService: AvailabilityWatchService by inject()
    val watchCapabilities: WatchCapabilityService by inject()
    val availabilityProviders: AvailabilityProviderRegistry by inject()
    val dateResolver: AvailabilityDateResolver by inject()
    val failoverFetcher: FailoverAvailabilityFetcher by inject()
    val poiService: PoiReader by inject()
    val poisOnRouteService: PoisOnRouteService by inject()
    val routeCache: RouteCache by inject()
    val routeCorridorService: RouteCorridorService by inject()
    val mapboxGeocoder: ca.floo.roadtrip.client.mapbox.MapboxGeocoder by inject()
    val ingestController: IngestController by inject()
    val slackInteractivity: SlackInteractivityWiring? = getKoin().getOrNull()
    val slackNotifications: SlackNotificationService by inject()
    val emailNotifications: EmailNotificationService by inject()
    val schedulerScope: CoroutineScope by inject()
    val staticDir: File by inject(named("staticDir"))

    routing {
        apiDocsRoutes()
        poiRoutes(poiService)
        availabilityWatchRoutes(availabilityWatchController(ctx, watchService, watchCapabilities))
        campsiteRoutes(
            ctx = ctx,
            availabilityProviders = availabilityProviders,
            dateResolver = dateResolver,
            failoverFetcher = failoverFetcher,
            watchCapabilities = watchCapabilities,
        )
        slackInteractivity?.let { wiring ->
            slackInteractivityRoute(wiring.verifier, wiring.handler, schedulerScope)
        }
        availabilityDashboardRoutes(
            availabilityDashboardController(ctx, config.availability.forcePullCooldown),
        )
        poisOnRouteRoutes(poisOnRouteService, config.route)
        routeRoutes(routeCache, routeCorridorService, config.route)
        geocodeRoutes(mapboxGeocoder)
        healthRoutes()
        adminIngestRoutes(ingestController, ctx)
        testEmailRoutes(emailNotifications, config.webApp?.rootUrl)
        testSlackRoutes(slackNotifications)
        staticSiteRoutes(staticDir)
    }
}

private fun availabilityDashboardController(
    ctx: DSLContext,
    forcePullCooldown: Duration,
): AvailabilityDashboardController =
    AvailabilityDashboardController(
        pollerRepo = AvailabilityPollerRepo(ctx),
        runRepo = AvailabilityRunRepo(ctx),
        availabilityRepo = AvailabilityRepo(ctx),
        campsiteRepo = CampsiteRepo(ctx),
        forcePullCooldown = forcePullCooldown,
    )

private fun availabilityWatchController(
    ctx: DSLContext,
    watchService: AvailabilityWatchService,
    watchCapabilities: WatchCapabilityService,
): AvailabilityWatchController {
    val campsitesRepo = CampsiteRepo(ctx)
    return AvailabilityWatchController(
        watchRepo = AvailabilityWatchRepo(ctx),
        watchService = watchService,
        watchMapper =
            AvailabilityWatchApiMapper(
                campsiteRepo = campsitesRepo,
                scopeResolver = WatchScopeResolver(campsitesRepo),
                watchCapabilityService = watchCapabilities,
            ),
    )
}
