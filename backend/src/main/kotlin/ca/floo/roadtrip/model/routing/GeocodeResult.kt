package ca.floo.roadtrip.model.routing

data class GeocodeResult(
    val id: String,
    val placeName: String,
    val lng: Double,
    val lat: Double,
    val placeType: String,
)
