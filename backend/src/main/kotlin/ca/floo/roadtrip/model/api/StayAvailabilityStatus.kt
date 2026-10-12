package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a whole stay looks at one campground: the one word a result card leads with. */
@Serializable
enum class StayAvailabilityStatus {
    /** More than a few sites are bookable online for every night of the stay. */
    @SerialName("open")
    OPEN,

    /** One or a few sites are. */
    @SerialName("few_left")
    FEW_LEFT,

    /** None is, and the provider said so for at least one night of one site. */
    @SerialName("full")
    FULL,

    /** Every site is closed on some night of the stay: the season ends, or has not begun, within it. */
    @SerialName("closed_for_season")
    CLOSED_FOR_SEASON,

    /** Nothing to judge by: no site matched, or the provider answered for none of the nights. */
    @SerialName("no_data")
    NO_DATA,
}
