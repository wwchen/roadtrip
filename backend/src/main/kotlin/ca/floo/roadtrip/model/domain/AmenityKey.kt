package ca.floo.roadtrip.model.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The campground amenity vocabulary. `wire` is the stored and API value; the labels ride to the frontend. */
@Serializable
enum class AmenityKey(
    val wire: String,
    val label: String,
    val negativeLabel: String? = null,
) {
    @SerialName("camp_store")
    CAMP_STORE("camp_store", "Camp store"),

    @SerialName("dump_station")
    DUMP_STATION("dump_station", "Dump station"),

    @SerialName("electric_hookups")
    ELECTRIC_HOOKUPS("electric_hookups", "Electric hookups", "No electric hookups"),

    @SerialName("fires_allowed")
    FIRES_ALLOWED("fires_allowed", "Fires allowed"),

    @SerialName("pets_allowed")
    PETS_ALLOWED("pets_allowed", "Pets allowed"),

    @SerialName("sewer_hookups")
    SEWER_HOOKUPS("sewer_hookups", "Sewer hookups", "No sewer hookups"),

    @SerialName("showers")
    SHOWERS("showers", "Showers", "No showers"),

    @SerialName("toilets")
    TOILETS("toilets", "Toilets"),

    @SerialName("trash")
    TRASH("trash", "Trash"),

    @SerialName("water")
    WATER("water", "Water", "No water"),

    @SerialName("water_hookups")
    WATER_HOOKUPS("water_hookups", "Water hookups", "No water hookups"),

    @SerialName("wifi")
    WIFI("wifi", "Wi-Fi"),

    @SerialName("other")
    OTHER("other", ""),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** A vendor key that already speaks the vocabulary; null when it does not (the caller maps it to [OTHER]). */
        fun fromWire(value: String): AmenityKey? = byWire[value]
    }
}
