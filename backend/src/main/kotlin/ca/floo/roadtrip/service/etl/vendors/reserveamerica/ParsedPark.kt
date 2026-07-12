package ca.floo.roadtrip.service.etl.vendors.reserveamerica

data class ParsedPark(
    val parkId: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val phone: String?,
    val description: String?,
    val photoUrl: String?,
    val infoUrl: String?,
)
