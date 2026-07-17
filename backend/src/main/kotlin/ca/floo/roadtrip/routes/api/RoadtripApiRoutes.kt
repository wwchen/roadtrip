package ca.floo.roadtrip.routes.api

import ca.floo.roadtrip.RoadtripRuntime
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.routes.api.admin.adminIngestRoutes
import ca.floo.roadtrip.routes.api.availability.availabilityDashboardRoutes
import ca.floo.roadtrip.routes.api.availability.availabilityWatchRoutes
import ca.floo.roadtrip.routes.api.docs.apiDocsRoutes
import ca.floo.roadtrip.routes.api.geocode.geocodeRoutes
import ca.floo.roadtrip.routes.api.health.healthRoutes
import ca.floo.roadtrip.routes.api.pois.campsiteRoutes
import ca.floo.roadtrip.routes.api.pois.poiRoutes
import ca.floo.roadtrip.routes.api.pois.poisOnRouteRoutes
import ca.floo.roadtrip.routes.api.route.routeRoutes
import ca.floo.roadtrip.routes.api.slack.slackInteractivityRoute
import ca.floo.roadtrip.routes.test.testEmailRoutes
import ca.floo.roadtrip.routes.test.testSlackRoutes
import ca.floo.roadtrip.service.availability.CampgroundAvailabilitySupport
import ca.floo.roadtrip.service.notification.email.EmailNotificationService
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
import ca.floo.roadtrip.service.poi.PoiDetailService
import ca.floo.roadtrip.service.poi.PoiService
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.poi.TeslaSuperchargerService
import ca.floo.roadtrip.service.readpath.ReadPathProviderPoiReader
import ca.floo.roadtrip.service.routing.RouteCorridorService
import io.ktor.server.routing.Route

internal fun Route.roadtripApiRoutes(runtime: RoadtripRuntime) {
    val campgroundAvailabilitySupport =
        CampgroundAvailabilitySupport(
            providerRefs = runtime.campsiteProviders,
            availabilityProviders = runtime.availabilityProviderRegistry,
        )
    val poiDetailServices: List<PoiDetailService> =
        listOf(
            CampgroundService(
                repo = CampgroundRepo(runtime.ctx),
                dateResolver = runtime.availabilityDateResolver,
                availabilitySupport = campgroundAvailabilitySupport,
            ),
            TeslaSuperchargerService(TeslaSuperchargerRepo(runtime.ctx)),
            PlanetFitnessLocationService(PlanetFitnessLocationRepo(runtime.ctx)),
        )
    val poiService =
        ReadPathProviderPoiReader(
            delegate =
                PoiService(
                    poiRepo = PoiServingRepo(runtime.ctx),
                    detailServices = poiDetailServices,
                ),
            detailServices = poiDetailServices,
            providers = runtime.appConfig.readPathProviders,
        )
    val routeCorridorService = RouteCorridorService(RouteCorridorRepo(runtime.ctx))
    val poisOnRouteService =
        PoisOnRouteService(
            routeCache = runtime.routeCache,
            routeCorridorService = routeCorridorService,
            poiService = poiService,
        )

    apiDocsRoutes()
    poiRoutes(poiService)
    availabilityWatchRoutes(
        runtime.ctx,
        runtime.availabilityWatchService,
        runtime.watchCapabilities,
    )
    campsiteRoutes(
        ctx = runtime.ctx,
        availabilityProviders = runtime.availabilityProviderRegistry,
        dateResolver = runtime.availabilityDateResolver,
        failoverFetcher = runtime.failoverFetcher,
        watchCapabilities = runtime.watchCapabilities,
    )
    runtime.slackInteractivity?.let { wiring ->
        slackInteractivityRoute(wiring.verifier, wiring.handler, runtime.schedulerScope)
    }
    availabilityDashboardRoutes(
        ctx = runtime.ctx,
        forcePullCooldown = runtime.appConfig.availability.forcePullCooldown,
    )
    poisOnRouteRoutes(poisOnRouteService)
    routeRoutes(runtime.routeCache, routeCorridorService)
    geocodeRoutes(runtime.mapboxGeocoder)
    healthRoutes()
    adminIngestRoutes(runtime.ingestController, runtime.ctx)
    testEmailRoutes(EmailNotificationService(runtime.appConfig.email))
    testSlackRoutes(runtime.slackNotifications)
}
