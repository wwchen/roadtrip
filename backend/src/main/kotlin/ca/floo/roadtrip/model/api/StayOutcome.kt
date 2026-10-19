package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What one campground's answer to a stay check amounts to, stated by the server
 * so the browser does not have to infer it from a failure's code string.
 *
 * [REFUSED] is strictly about the dates: the campground understood the question
 * and the stay cannot work there, so asking again is pointless. [FAILED] means we
 * did not get an answer (see [StayFailureReason] for why and whether to retry),
 * and [TIMED_OUT] means the campground did not answer in time. [ANSWERED]
 * carries the stay summary.
 */
@Serializable
enum class StayOutcome {
    @SerialName("answered")
    ANSWERED,

    @SerialName("refused")
    REFUSED,

    @SerialName("timed_out")
    TIMED_OUT,

    @SerialName("failed")
    FAILED,
}
