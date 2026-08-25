package ca.floo.roadtrip.di

import ca.floo.roadtrip.repo.AdminIngestReadRepo
import ca.floo.roadtrip.repo.ApiCacheRepo
import ca.floo.roadtrip.repo.AvailabilityFetchCallRepo
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.DatabaseHealthRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.RefLinkRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.repo.WatchAccessTokenRepo
import ca.floo.roadtrip.service.ref.DbRefResolver
import ca.floo.roadtrip.service.ref.RefResolver
import org.koin.dsl.module

val repoModule =
    module {
        single { CampsiteRepo(get()) }
        single { PoiRepo(get()) }
        single { RefLinkRepo(get()) }
        single<RefResolver> { DbRefResolver(get()) }
        single { AvailabilityRepo(get()) }
        single { AvailabilityWatchRepo(get()) }
        single { AvailabilityPollerRepo(get()) }
        single { AvailabilityRunRepo(get()) }
        single { AvailabilityFetchCallRepo(get()) }
        single { AvailabilityWatchTargetRepo(get()) }
        single { ApiCacheRepo(get()) }
        single { CampgroundRepo(get()) }
        single { TeslaSuperchargerRepo(get()) }
        single { PlanetFitnessLocationRepo(get()) }
        single { RouteCorridorRepo(get()) }
        single { AdminIngestReadRepo(get()) }
        single { UserRepo(get()) }
        single { UserSettingsRepo(get()) }
        single { WatchAccessTokenRepo(get()) }
        single { DatabaseHealthRepo(get()) }
    }
