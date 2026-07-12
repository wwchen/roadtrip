package ca.floo.roadtrip.models.domain

data class CampgroundProviderRefRow(
    val campgroundId: Long,
    val source: String,
    val providerRefJson: String,
)
