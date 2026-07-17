package ca.floo.roadtrip.model.domain

data class AspiraCampgroundParentCandidate(
    val campgroundId: Long,
    val vendor: String,
    val externalId: String,
    val resourceLocationId: String?,
)
