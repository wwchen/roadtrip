package ca.floo.roadtrip.model.availability

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A watch's lifecycle state. [wireValue] is both the JSON value and the
 * `availability_watch.status` column value; the `@SerialName`s repeat it so the
 * generated TypeScript union is the same vocabulary.
 */
@Serializable
enum class WatchStatus(
    val wireValue: String,
) {
    @SerialName("active")
    ACTIVE("active"),

    @SerialName("paused")
    PAUSED("paused"),

    @SerialName("done")
    DONE("done"),
    ;

    companion object {
        fun parse(value: String?): WatchStatus? = entries.firstOrNull { it.wireValue == value }
    }
}
