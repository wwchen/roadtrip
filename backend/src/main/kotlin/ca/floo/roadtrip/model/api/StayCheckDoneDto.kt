package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

/** The last event of a stay check's stream: every requested POI has answered. */
@Serializable
data class StayCheckDoneDto(
    /** How many answers the stream carried — one per requested POI. */
    val answered: Int,
) {
    companion object {
        /** The `event:` name this is sent under. */
        const val EVENT = "done"
    }
}
