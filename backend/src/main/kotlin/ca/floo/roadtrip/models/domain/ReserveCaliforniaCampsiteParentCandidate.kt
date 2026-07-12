package ca.floo.roadtrip.models.domain

data class ReserveCaliforniaCampsiteParentCandidate(
    val campsiteId: Long,
    val vendorRefPlaceId: String?,
    val sourceParentPlaceId: String?,
)
