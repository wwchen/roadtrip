package ca.floo.roadtrip.models.domain

data class AspiraCampsiteParentCandidate(
    val campsiteId: Long,
    val vendor: String,
    val transactionLocationId: String?,
    val mapId: String?,
    val vendorRefResourceLocationId: String?,
    val sourceParentResourceLocationId: String?,
)
