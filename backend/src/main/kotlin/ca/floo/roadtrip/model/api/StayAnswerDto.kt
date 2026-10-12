package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One campground's answer in a stay check's stream: how the stay looks there,
 * or the code it failed with. Exactly one of [summary] and [error] is set.
 */
@Serializable
data class StayAnswerDto(
    @SerialName("poi_id") val poiId: Long,
    val summary: BulkPoiSummaryDto? = null,
    val error: String? = null,
) {
    companion object {
        /** The `event:` name this is sent under. */
        const val EVENT = "answer"
    }
}
