package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One campground's answer in a stay check's stream. [outcome] says which of
 * four things it is, and decides which of the other fields are present:
 *
 * - [StayOutcome.ANSWERED]: [stay] is set; [reason] and [retryable] are absent.
 * - [StayOutcome.REFUSED]: [reason] is set (one of the date refusals); [stay] and
 *   [retryable] are absent, since asking again with the same dates cannot help.
 * - [StayOutcome.FAILED]: [reason] and [retryable] are set; [stay] is absent.
 * - [StayOutcome.TIMED_OUT]: only [outcome] — a timeout has no reason and is
 *   always worth asking again, so neither field says anything.
 *
 * A browser reads [outcome] first and the rest by it; it never has to infer what
 * a failure means from a code string.
 */
@Serializable
data class StayAnswerDto(
    @SerialName("poi_id") val poiId: Long,
    val outcome: StayOutcome,
    val stay: StaySummaryDto? = null,
    val reason: StayFailureReason? = null,
    val retryable: Boolean? = null,
) {
    companion object {
        /** The `event:` name this is sent under. */
        const val EVENT = "answer"
    }
}
