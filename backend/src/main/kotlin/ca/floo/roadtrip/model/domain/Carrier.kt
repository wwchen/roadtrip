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
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        // The names rec.gov's rating aggregate ships. Kept apart from `label` so
        // rewording the frontend label can never break ingest.
        private val byVendorName =
            mapOf(
                "verizon" to VERIZON,
                "at&t" to ATT,
                "t-mobile" to TMOBILE,
                "sprint" to SPRINT,
                "us cellular" to US_CELLULAR,
            )

        /** A vendor key that already speaks the vocabulary (Campflare's cell-service keys). */
        fun fromWire(value: String): Carrier? = byWire[value]

        /** A vendor's display name for the carrier; null drops the reading, as it always has. */
        fun fromVendorName(name: String): Carrier? = byVendorName[name.trim().lowercase()]
    }
}
