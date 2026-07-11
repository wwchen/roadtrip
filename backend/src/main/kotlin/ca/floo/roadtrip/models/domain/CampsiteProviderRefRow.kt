package ca.floo.roadtrip.models.domain

data class CampsiteProviderRefRow(
    val poiId: Long,
    val source: String,
    val providerRefJson: String,
    val lng: Double? = null,
    val lat: Double? = null,
)
