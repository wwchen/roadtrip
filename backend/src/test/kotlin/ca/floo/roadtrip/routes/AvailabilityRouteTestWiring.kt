package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityQueryServiceImpl
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.ReservableAvailabilityComposer
import ca.floo.roadtrip.service.availability.defaultSnapshotFreshnessTtl
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import io.ktor.server.routing.Route
import java.time.Clock
import java.time.Duration

internal fun Route.availabilityRoutes(
    providerRefs: CampsiteProviderRepo,
    reservationProviders: ReservationProviderRegistry,
    reservablesRepo: ReservableRepo,
    availability: AvailabilityRepo? = null,
    snapshotFreshnessTtl: (ReservationProviderId) -> Duration = ::defaultSnapshotFreshnessTtl,
    clock: Clock = Clock.systemUTC(),
) {
    val dateResolver = AvailabilityDateResolver(clock)
    val targets =
        DbAvailabilityTargetResolver(
            providerRefs = providerRefs,
            reservablesRepo = reservablesRepo,
            reservationProviders = reservationProviders,
            dateResolver = dateResolver,
        )
    val composer =
        ReservableAvailabilityComposer(
            targets = targets,
            dateResolver = dateResolver,
            availability = availability,
            snapshotFreshnessTtl = snapshotFreshnessTtl,
        )
    availabilityRoutes(
        routeService =
            AvailabilityQueryServiceImpl(
                providerRefs = providerRefs,
                reservablesRepo = reservablesRepo,
                composer = composer,
                dateResolver = dateResolver,
                reservationProviders = reservationProviders,
            ),
    )
}
