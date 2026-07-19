package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.BookingProvider
import ca.floo.roadtrip.model.domain.BookingRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser

internal class CampgroundAvailabilitySupport(
    private val campsiteProviderRepo: CampsiteProviderRepo,
    private val availabilityProviders: AvailabilityProviderRegistry,
) {
    fun preferredAvailabilityProvider(campgroundId: Long): String? =
        campsiteProviderRepo
            .findCampgroundProviderRefCandidates(campgroundId)
            .firstNotNullOfOrNull { candidate ->
                resolveViaBookingRef(candidate.source, candidate.bookingProviderRef)
                    ?: resolveViaLegacyJson(candidate.source, candidate.providerRefJson)
            }

    private fun resolveViaBookingRef(
        source: String,
        bookingProviderRef: String?,
    ): String? {
        val bp = BookingProvider.fromIdOrNull(source) ?: return null
        val bpRef = bookingProviderRef ?: return null
        val bookingRef = BookingRef.parse(bp, bpRef) ?: return null
        return availabilityProviders
            .forBooking(bp, bookingRef)
            ?.id
            ?.name
            ?.lowercase()
    }

    private fun resolveViaLegacyJson(
        source: String,
        providerRefJson: String,
    ): String? {
        val ref = ProviderRefParser.parse(providerRefJson) ?: return null
        return availabilityProviders
            .forSource(source)
            ?.takeIf { it.supportsRef(ref) }
            ?.id
            ?.name
            ?.lowercase()
    }
}
