package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiCtaSchema
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovBookingDisplay
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovBookingUrl

internal object RecGovPoiCtaProvider : PoiCtaProvider {
    override fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? ProviderRef.RecGov)?.let { RecGovBookingDisplay.BOOKING_SYSTEM_LABEL }

    override fun reserveCta(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        val recgov = providerRef as? ProviderRef.RecGov ?: return null
        return reserveCta(
            url = RecGovBookingUrl.campground(recgov.recgovId),
            label = RecGovBookingDisplay.CAMPGROUND_CTA_LABEL,
        )
    }
}
