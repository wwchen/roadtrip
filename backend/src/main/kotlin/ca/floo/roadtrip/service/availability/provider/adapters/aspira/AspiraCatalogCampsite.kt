package ca.floo.roadtrip.service.availability.provider.adapters.aspira

internal data class AspiraCatalogCampsite(
    val campsiteId: Long,
    val resourceId: String,
    val mapId: Int?,
    val resourceLocationId: Int? = null,
)
