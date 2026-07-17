package ca.floo.roadtrip.model.availability

data class CatalogCampsiteRef(
    val campsiteId: Long,
    val vendorId: String,
    val mapId: Long? = null,
    val resourceLocationId: Long? = null,
)
