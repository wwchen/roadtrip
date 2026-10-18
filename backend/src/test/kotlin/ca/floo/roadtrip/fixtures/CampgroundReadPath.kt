package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.api.AvailabilityLoader
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityReadPolicy
import ca.floo.roadtrip.service.availability.BookingHorizonResolver
import ca.floo.roadtrip.service.availability.CampgroundAvailabilityReader
import ca.floo.roadtrip.service.availability.FailoverAvailabilityFetcher
import ca.floo.roadtrip.service.availability.defaultSnapshotFreshnessTtl
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.Clock
import java.time.Duration

internal class CampgroundReadPath(
    val policy: AvailabilityReadPolicy,
    val reader: CampgroundAvailabilityReader,
)

internal fun testCampgroundReadPath(
    providers: List<AvailabilityProvider>,
    dateResolver: AvailabilityDateResolver,
    failoverFetcher: FailoverAvailabilityFetcher,
    availabilityRepo: AvailabilityRepo?,
    clock: Clock = Clock.systemUTC(),
    snapshotFreshnessTtl: (AvailabilityProvider) -> Duration = { defaultSnapshotFreshnessTtl(it.id) },
): CampgroundReadPath {
    val policy =
        AvailabilityReadPolicy(
            providers = providers,
            dateResolver = dateResolver,
            bookingHorizons = BookingHorizonResolver(providers, dateResolver),
            clock = clock,
            snapshotFreshnessTtl = snapshotFreshnessTtl,
        )
    return CampgroundReadPath(
        policy = policy,
        reader =
            CampgroundAvailabilityReader(
                policy = policy,
                loader = AvailabilityLoader(availabilityRepo, clock),
                failoverFetcher = failoverFetcher,
                availabilityRepo = availabilityRepo,
            ),
    )
}
