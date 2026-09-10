package ca.floo.roadtrip.model.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The one campsite-type vocabulary. [wire] is what `campsites.kind`, the
 * `site_type` filters and the API carry; [label] is the backend's display
 * wording, so a client never has to own a translation table. The vendor's own
 * words stay on `kind_listed`.
 */
@Serializable
enum class CampsiteKind(
    val wire: String,
    val label: String,
) {
    @SerialName("standard")
    STANDARD("standard", "Standard"),

    @SerialName("tent")
    TENT("tent", "Tent"),

    @SerialName("rv")
    RV("rv", "RV"),

    @SerialName("cabin")
    CABIN("cabin", "Cabin"),

    @SerialName("group")
    GROUP("group", "Group"),

    @SerialName("walk_in")
    WALK_IN("walk_in", "Walk-in"),

    @SerialName("boat_in")
    BOAT_IN("boat_in", "Boat-in"),

    @SerialName("equestrian")
    EQUESTRIAN("equestrian", "Equestrian"),

    @SerialName("backcountry")
    BACKCOUNTRY("backcountry", "Backcountry"),

    @SerialName("day_use")
    DAY_USE("day_use", "Day use"),

    @SerialName("other")
    OTHER("other", "Other"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(value: String): CampsiteKind? = byWire[value]
    }
}
