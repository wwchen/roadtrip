package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
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
                resolveViaBookingProviderRef(candidate.source, candidate.bookingProviderRef)
                    ?: resolveViaLegacyJson(candidate.source, candidate.providerRefJson)
            }

    private fun resolveViaBookingProviderRef(
        source: String,
        bookingProviderRef: String?,
    ): String? {
        val bp = BookingProvider.fromIdOrNull(source) ?: return null
        val bpRef = bookingProviderRef ?: return null
        val bookingRef = BookingProviderRef.parse(bp, bpRef) ?: return null
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
