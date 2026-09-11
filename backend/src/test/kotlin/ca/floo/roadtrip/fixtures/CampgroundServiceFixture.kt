package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.BookingIdentityResolver
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import org.jooq.DSLContext

/** The campground drawer as it ships: the shipped registry decides who sells. */
internal fun testCampgroundService(
    ctx: DSLContext,
    availabilityProviders: List<AvailabilityProvider> = emptyList(),
): CampgroundService =
    CampgroundService(
        campgroundRepo = CampgroundRepo(ctx),
        dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
        bookingHorizons = testBookingHorizons(ctx, availabilityProviders),
        identities = BookingIdentityResolver(shippedTenantRegistry()),
        cta = CampgroundCta.default,
    )
