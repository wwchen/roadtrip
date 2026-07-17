package ca.floo.roadtrip

import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.config.AppConfig
import ca.floo.roadtrip.repo.CampgroundRepo
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
import io.ktor.server.plugins.di.resolve
import kotlinx.coroutines.CoroutineScope
import org.jooq.DSLContext

internal fun Application.installRoadtripDependencies(boot: RoadtripBootContext) {
    dependencies {
        provide<RoadtripBootContext> { boot }
        provide<AppConfig> { resolve<RoadtripBootContext>().appConfig }
        provide<DSLContext> { resolve<RoadtripBootContext>().ctx }
        provide(::startRoadtripRuntime)
        provide<AvailabilityProviderRegistry> { resolve<RoadtripRuntime>().availabilityProviderRegistry }
        provide<AvailabilityDateResolver> { resolve<RoadtripRuntime>().availabilityDateResolver }
        provide<AvailabilityWatchService> { resolve<RoadtripRuntime>().availabilityWatchService }
        provide<WatchAlertDispatcher> { resolve<RoadtripRuntime>().watchAlertDispatcher }
        provide<WatchCapabilityService> { resolve<RoadtripRuntime>().watchCapabilities }
        provide<FailoverAvailabilityFetcher> { resolve<RoadtripRuntime>().failoverFetcher }
        provide<CoroutineScope> { resolve<RoadtripRuntime>().schedulerScope }
        provide<MapboxGeocoder> { resolve<RoadtripRuntime>().mapboxGeocoder }
        provide<RouteCache> { resolve<RoadtripRuntime>().routeCache }
        provide { CampgroundRepo(resolve()) }
        provide { TeslaSuperchargerRepo(resolve()) }
        provide { PlanetFitnessLocationRepo(resolve()) }
        provide { PoiServingRepo(resolve()) }
        provide { RouteCorridorRepo(resolve()) }
        provide {
            CampgroundAvailabilitySupport(
                providerRefs = resolve<RoadtripRuntime>().campsiteProviders,
                availabilityProviders = resolve(),
            )
        }
        provide {
            CampgroundService(
                repo = resolve(),
                dateResolver = resolve(),
                availabilitySupport = resolve(),
            )
        }
        provide { TeslaSuperchargerService(resolve()) }
        provide { PlanetFitnessLocationService(resolve()) }
        provide<List<PoiDetailService>> {
            listOf(
                resolve<CampgroundService>(),
                resolve<TeslaSuperchargerService>(),
                resolve<PlanetFitnessLocationService>(),
            )
        }
        provide {
            PoiService(
                poiRepo = resolve(),
                detailServices = resolve(),
            )
        }
        provide<PoiReader> {
            ReadPathProviderPoiReader(
                delegate = resolve<PoiService>(),
                detailServices = resolve(),
                providers = resolve<AppConfig>().readPathProviders,
            )
        }
        provide { RouteCorridorService(resolve()) }
        provide {
            PoisOnRouteService(
                routeCache = resolve(),
                routeCorridorService = resolve(),
                poiService = resolve(),
            )
        }
    }
}

internal fun Application.installRoadtripAdminDependencies(boot: RoadtripBootContext) {
    dependencies {
        provide<RoadtripBootContext> { boot }
    }
}
