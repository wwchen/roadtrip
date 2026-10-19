package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

/** The last event of a stay check's stream: every requested POI has answered, and [outcome] says how the check ended. */
@Serializable
data class StayCheckDoneDto(
    val outcome: StayCheckOutcome,
) {
    companion object {
        /** The `event:` name this is sent under. */
        const val EVENT = "done"
    }
}
