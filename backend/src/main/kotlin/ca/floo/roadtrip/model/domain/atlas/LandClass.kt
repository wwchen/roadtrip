package ca.floo.roadtrip.model.domain.atlas

/**
 * How a campground's managing agency is bucketed in the Atlas index. Declaration
 * order is the display order (National Parks first, then State & Provincial
 * Parks, then Open Lands, then everything else).
 */
enum class LandClass(
    val key: String,
    val label: String,
) {
    NATIONAL_PARK("national", "National Parks"),
    STATE_PARK("state", "State & Provincial Parks"),
    OPEN_LAND("open", "Open Lands"),
    OTHER("other", "Other"),
    ;

    companion object {
        fun fromKey(key: String): LandClass? = entries.firstOrNull { it.key == key }
    }
}
