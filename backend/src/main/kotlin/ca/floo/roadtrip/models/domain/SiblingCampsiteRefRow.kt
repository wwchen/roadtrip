package ca.floo.roadtrip.models.domain

/**
 * One representative campsite row's vendor-ref payload for a specific vendor,
 * resolved by joining across the campsite's match group. Callers use this to
 * translate a sibling-vendor candidate's identity while keeping observations
 * anchored to the representative id.
 */
data class SiblingCampsiteRefRow(
    val representativeCampsiteId: Long,
    val providerRefJson: String,
    val externalId: String,
)
