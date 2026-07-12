package ca.floo.roadtrip.models.domain

data class AspiraCampgroundParentCandidate(
    val campgroundId: Long,
    val vendor: String,
    val externalId: String,
    val resourceLocationId: String?,
)
