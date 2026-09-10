package ca.floo.roadtrip.model.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The cell-coverage carrier vocabulary. `wire` is the stored and API value. */
@Serializable
enum class Carrier(
    val wire: String,
    val label: String,
) {
    @SerialName("verizon")
    VERIZON("verizon", "Verizon"),

    @SerialName("att")
    ATT("att", "AT&T"),

    @SerialName("tmobile")
    TMOBILE("tmobile", "T-Mobile"),

    @SerialName("sprint")
    SPRINT("sprint", "Sprint"),

    @SerialName("uscell")
    US_CELLULAR("uscell", "US Cellular"),
}
