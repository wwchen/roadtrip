package ca.floo.roadtrip.model.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The `campgrounds.metadata` JSONB object: what the vendor knows that has no column of its own. */
@Serializable
data class CampgroundMetadata(
    val activities: List<String> = emptyList(),
    val rating: CampgroundRating? = null,
    @SerialName("last_updated") val lastUpdated: String? = null,
)
