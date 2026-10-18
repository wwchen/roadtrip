package ca.floo.roadtrip.service.availability.provider

internal data class AspiraCampsite(
    val campsiteId: Long,
    val resourceId: String,
    val mapId: Int,
    val resourceLocationId: Int? = null,
)
