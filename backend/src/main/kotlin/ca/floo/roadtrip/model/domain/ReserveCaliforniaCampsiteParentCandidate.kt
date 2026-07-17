package ca.floo.roadtrip.model.domain

data class ReserveCaliforniaCampsiteParentCandidate(
    val campsiteId: Long,
    val vendorRefPlaceId: String?,
    val sourceParentPlaceId: String?,
)
