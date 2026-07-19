package ca.floo.roadtrip.model.domain

import ca.floo.roadtrip.model.domain.provider.BookingProvider

data class CampsiteProviderRefRow(
    val poiId: Long,
    val source: String,
    val providerRefJson: String,
    val bookingProvider: BookingProvider? = null,
    val bookingProviderRef: String? = null,
    val lng: Double? = null,
    val lat: Double? = null,
)
