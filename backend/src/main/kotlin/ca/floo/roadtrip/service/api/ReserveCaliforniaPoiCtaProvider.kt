package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiCtaSchema
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.ReserveCaliforniaBookingDisplay
import ca.floo.roadtrip.service.availability.provider.adapters.reservecalifornia.ReserveCaliforniaBookingUrl

internal object ReserveCaliforniaPoiCtaProvider : PoiCtaProvider {
    override fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? = (providerRef as? ProviderRef.ReserveCalifornia)?.let { ReserveCaliforniaBookingDisplay.BOOKING_SYSTEM_LABEL }

    override fun reserveCta(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? {
        val reserveCalifornia = providerRef as? ProviderRef.ReserveCalifornia ?: return null
        return reserveCta(
            url = ReserveCaliforniaBookingUrl.park(reserveCalifornia.placeId),
            label = ReserveCaliforniaBookingDisplay.PARK_CTA_LABEL,
        )
    }
}
