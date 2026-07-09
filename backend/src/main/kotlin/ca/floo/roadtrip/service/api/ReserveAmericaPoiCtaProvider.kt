package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaBookingDisplay

internal object ReserveAmericaPoiCtaProvider : PoiCtaProvider {
    override fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? ProviderRef.ReserveAmerica)?.let { ReserveAmericaBookingDisplay.BOOKING_SYSTEM_LABEL }
}
