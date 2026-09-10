package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.BookingHorizonResolver
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import org.jooq.DSLContext

/**
 * A horizon resolver over [providers]; with none registered every campground
 * resolves to a null `latest_date`, which is what POI-detail tests that do not
 * care about the picker's ceiling want.
 */
internal fun testBookingHorizons(
    ctx: DSLContext,
    providers: List<AvailabilityProvider> = emptyList(),
): BookingHorizonResolver = BookingHorizonResolver(providers, AvailabilityDateResolver(PoiRepo(ctx)))
