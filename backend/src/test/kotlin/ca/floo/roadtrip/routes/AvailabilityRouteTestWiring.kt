package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityServiceImpl
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
    val availabilityService =
        AvailabilityServiceImpl(
            providerRefs = providerRefs,
            reservationProviders = reservationProviders,
            reservablesRepo = reservablesRepo,
            dateResolver = dateResolver,
            snapshots = snapshots,
            snapshotFreshnessTtl = snapshotFreshnessTtl,
        )
    availabilityRoutes(
        availabilityService = availabilityService,
        controller =
            AvailabilityRouteController(
                providerRefs = providerRefs,
                reservablesRepo = reservablesRepo,
                availabilityService = availabilityService,
                dateResolver = dateResolver,
            ),
    )
}
