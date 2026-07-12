package ca.floo.roadtrip.service.availability.provider

data class CatalogCampsiteRef(
    val campsiteId: Long,
    val vendorId: String,
    val mapId: Long? = null,
    val resourceLocationId: Long? = null,
)
