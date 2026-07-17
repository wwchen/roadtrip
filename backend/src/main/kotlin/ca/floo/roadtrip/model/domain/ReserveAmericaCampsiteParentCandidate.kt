package ca.floo.roadtrip.model.domain

data class ReserveAmericaCampsiteParentCandidate(
    val campsiteId: Long,
    val vendorRefParentContractCode: String?,
    val sourceParentContractCode: String?,
    val vendorRefParentParkId: String?,
    val sourceParentParkId: String?,
)
