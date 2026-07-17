package ca.floo.roadtrip

import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.CampgroundAvailabilitySupport
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
import ca.floo.roadtrip.service.poi.PoiDetailService
import ca.floo.roadtrip.service.poi.PoiReader
import ca.floo.roadtrip.service.poi.PoiService
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.poi.TeslaSuperchargerService
import ca.floo.roadtrip.service.readpath.ReadPathProviderPoiReader
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import kotlinx.coroutines.CoroutineScope
import org.jooq.DSLContext

internal fun Application.installRoadtripDependencies(boot: RoadtripBootContext) {
    dependencies {
        provide<RoadtripBootContext> { boot }
        provide(::appConfig)
        provide(::dslContext)
        provide(::startRoadtripRuntime)
        provide(::availabilityProviderRegistry)
        provide(::availabilityDateResolver)
        provide(::availabilityWatchService)
        provide(::watchAlertDispatcher)
        provide(::watchCapabilityService)
        provide(::failoverAvailabilityFetcher)
        provide(::schedulerScope)
        provide(::mapboxGeocoder)
        provide(::routeCache)
        provide(::campsiteProviderRepo)
        provide(::CampgroundRepo)
        provide(::TeslaSuperchargerRepo)
        provide(::PlanetFitnessLocationRepo)
        provide(::PoiServingRepo)
        provide(::RouteCorridorRepo)
        provide(::campgroundAvailabilitySupport)
        provide(::campgroundService)
        provide(::TeslaSuperchargerService)
        provide(::PlanetFitnessLocationService)
        provide(::poiDetailServices)
        provide(::poiService)
        provide(::poiReader)
        provide(::RouteCorridorService)
        provide(::poisOnRouteService)
    }
}

internal fun Application.installRoadtripAdminDependencies(boot: RoadtripBootContext) {
    dependencies {
        provide<RoadtripBootContext> { boot }
    }
}

internal fun appConfig(boot: RoadtripBootContext): AppConfig = boot.appConfig

internal fun dslContext(boot: RoadtripBootContext): DSLContext = boot.ctx

internal fun availabilityProviderRegistry(runtime: RoadtripRuntime): AvailabilityProviderRegistry = runtime.availabilityProviderRegistry

internal fun availabilityDateResolver(runtime: RoadtripRuntime): AvailabilityDateResolver = runtime.availabilityDateResolver

internal fun availabilityWatchService(runtime: RoadtripRuntime): AvailabilityWatchService = runtime.availabilityWatchService

internal fun watchAlertDispatcher(runtime: RoadtripRuntime): WatchAlertDispatcher = runtime.watchAlertDispatcher

internal fun watchCapabilityService(runtime: RoadtripRuntime): WatchCapabilityService = runtime.watchCapabilities

internal fun failoverAvailabilityFetcher(runtime: RoadtripRuntime): FailoverAvailabilityFetcher = runtime.failoverFetcher

internal fun schedulerScope(runtime: RoadtripRuntime): CoroutineScope = runtime.schedulerScope

internal fun mapboxGeocoder(runtime: RoadtripRuntime): MapboxGeocoder = runtime.mapboxGeocoder

internal fun routeCache(runtime: RoadtripRuntime): RouteCache = runtime.routeCache

internal fun campsiteProviderRepo(runtime: RoadtripRuntime): CampsiteProviderRepo = runtime.campsiteProviders

internal fun campgroundAvailabilitySupport(
    providerRefs: CampsiteProviderRepo,
    availabilityProviders: AvailabilityProviderRegistry,
): CampgroundAvailabilitySupport =
    CampgroundAvailabilitySupport(
        providerRefs = providerRefs,
        availabilityProviders = availabilityProviders,
    )

internal fun campgroundService(
    repo: CampgroundRepo,
    dateResolver: AvailabilityDateResolver,
    availabilitySupport: CampgroundAvailabilitySupport,
): CampgroundService =
    CampgroundService(
        repo = repo,
        dateResolver = dateResolver,
        availabilitySupport = availabilitySupport,
    )

internal fun poiDetailServices(
    campgroundService: CampgroundService,
    teslaSuperchargerService: TeslaSuperchargerService,
    planetFitnessLocationService: PlanetFitnessLocationService,
): List<PoiDetailService> =
    listOf(
        campgroundService,
        teslaSuperchargerService,
        planetFitnessLocationService,
    )

internal fun poiService(
    poiRepo: PoiServingRepo,
    detailServices: List<PoiDetailService>,
): PoiService =
    PoiService(
        poiRepo = poiRepo,
        detailServices = detailServices,
    )

internal fun poiReader(
    appConfig: AppConfig,
    poiService: PoiService,
    detailServices: List<PoiDetailService>,
): PoiReader =
    ReadPathProviderPoiReader(
        delegate = poiService,
        detailServices = detailServices,
        providers = appConfig.readPathProviders,
    )

internal fun poisOnRouteService(
    routeCache: RouteCache,
    routeCorridorService: RouteCorridorService,
    poiService: PoiReader,
): PoisOnRouteService =
    PoisOnRouteService(
        routeCache = routeCache,
        routeCorridorService = routeCorridorService,
        poiService = poiService,
    )
