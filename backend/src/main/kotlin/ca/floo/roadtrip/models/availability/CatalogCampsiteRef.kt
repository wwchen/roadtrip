package ca.floo.roadtrip.models.availability

data class CatalogCampsiteRef(
    val campsiteId: Long,
    val vendorId: String,
    val mapId: Long? = null,
    val resourceLocationId: Long? = null,
)
