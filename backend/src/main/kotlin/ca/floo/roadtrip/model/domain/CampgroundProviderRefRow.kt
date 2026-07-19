package ca.floo.roadtrip.model.domain

data class CampgroundProviderRefRow(
    val campgroundId: Long,
    val source: String,
    val providerRefJson: String,
    val bookingProviderRef: String? = null,
)
