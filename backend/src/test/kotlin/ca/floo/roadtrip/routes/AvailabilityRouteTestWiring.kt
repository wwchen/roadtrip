package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityQueryServiceImpl
import ca.floo.roadtrip.service.availability.AvailabilityServiceImpl
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.defaultSnapshotFreshnessTtl
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import io.ktor.server.routing.Route
import java.time.Duration

internal fun Route.availabilityRoutes(
    providerRefs: CampsiteProviderRepo,
    reservationProviders: ReservationProviderRegistry,
    reservablesRepo: ReservableRepo,
    snapshots: AvailabilitySnapshotRepo? = null,
    snapshotFreshnessTtl: (ReservationProviderId) -> Duration = ::defaultSnapshotFreshnessTtl,
) {
    val dateResolver = AvailabilityDateResolver()
    val targets =
        AvailabilityTargetResolver(
            providerRefs = providerRefs,
            reservablesRepo = reservablesRepo,
            reservationProviders = reservationProviders,
            dateResolver = dateResolver,
        )
    val availabilityService =
        AvailabilityServiceImpl(
            targets = targets,
            dateResolver = dateResolver,
            snapshots = snapshots,
            snapshotFreshnessTtl = snapshotFreshnessTtl,
        )
    availabilityRoutes(
        availabilityService = availabilityService,
        routeService =
            AvailabilityQueryServiceImpl(
                providerRefs = providerRefs,
                reservablesRepo = reservablesRepo,
                availabilityService = availabilityService,
                dateResolver = dateResolver,
                reservationProviders = reservationProviders,
            ),
    )
}
