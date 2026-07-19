package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.ref.RefResolver
import ca.floo.roadtrip.service.ref.RefValue
import ca.floo.roadtrip.service.ref.resolve

internal class CampgroundAvailabilitySupport(
    private val refResolver: RefResolver,
    private val availabilityProviders: AvailabilityProviderRegistry,
) {
    fun preferredAvailabilityProvider(campgroundId: Long): String? {
        val bookingRefs = refResolver.resolve<RefValue.CampgroundBookingRef>(RefValue.CampgroundId(campgroundId))
        return bookingRefs.firstNotNullOfOrNull { refValue ->
            availabilityProviders
                .forBooking(refValue.ref.provider, refValue.ref)
                ?.id
                ?.name
                ?.lowercase()
        }
    }
}
