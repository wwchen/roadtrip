package ca.floo.roadtrip.model.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a watch reached [WatchStatus.DONE]. Null on any row that went done before
 * `V63` — the transition did not record it, and nothing backfills a guess.
 */
@Serializable
enum class WatchDoneReason(
    val wireValue: String,
) {
    /** An alert was delivered on a watch with `stop_when_triggered`. */
    @SerialName("triggered")
    TRIGGERED("triggered"),

    /** The watch's own window passed; the reaper retired it. */
    @SerialName("elapsed")
    ELAPSED("elapsed"),
    ;

    companion object {
        fun parse(value: String?): WatchDoneReason? = entries.firstOrNull { it.wireValue == value }
    }
}
