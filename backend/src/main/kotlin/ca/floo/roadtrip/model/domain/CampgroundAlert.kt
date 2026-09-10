package ca.floo.roadtrip.model.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One entry of the `campgrounds.alerts` JSONB array. */
@Serializable
data class CampgroundAlert(
    val title: String? = null,
    val body: String,
    @SerialName("ends_on") val endsOn: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
)
