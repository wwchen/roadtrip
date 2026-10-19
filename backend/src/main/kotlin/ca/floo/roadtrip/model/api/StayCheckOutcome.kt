package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a stay check's stream ended, stated by the server because the browser
 * cannot tell: either way every requested POI has an answer by `done`, so
 * counting them or looking for gaps reads a truncated check as a finished one.
 */
@Serializable
enum class StayCheckOutcome {
    /** Every campground answered, or ran out its own per-POI timeout, before the stream's deadline. */
    @SerialName("complete")
    COMPLETE,

    /**
     * The whole stream hit its deadline and the server answered `timed_out` for each
     * campground still waiting. The answers are all present; some are not real ones.
     */
    @SerialName("deadline_reached")
    DEADLINE_REACHED,
}
