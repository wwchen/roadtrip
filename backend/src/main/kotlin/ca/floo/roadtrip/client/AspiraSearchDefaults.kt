package ca.floo.roadtrip.client

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.time.LocalDate

object AspiraSearchDefaults {
    const val SEARCH_TAB_GROUP_ID = 0
    const val BOOKING_CATEGORY_ID = 0
    const val ANY_EQUIPMENT_CATEGORY_ID = -32768
    const val ANY_SUB_EQUIPMENT_CATEGORY_ID = -32768
    const val DEFAULT_CAPACITY_CATEGORY_ID = -32767
    const val DEFAULT_PEOPLE_COUNT = 1
    const val NO_EQUIPMENT_COUNT = 0
    const val DEFAULT_BOAT_LENGTH = 0
    const val DEFAULT_BOAT_DRAFT = 0
    const val DEFAULT_BOAT_WIDTH = 0
    const val FLEXIBLE_SEARCH_RANGE_DAYS = 1

    fun occupancyPeopleCapacityCategoryCounts(): String =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("capacityCategoryId", JsonPrimitive(DEFAULT_CAPACITY_CATEGORY_ID))
                    put("subCapacityCategoryId", JsonNull)
                    put("count", JsonPrimitive(DEFAULT_PEOPLE_COUNT))
                    put("isAdult", JsonNull)
                },
            )
        }.toString()

    fun deeplinkPeopleCapacityCategoryCounts(): String =
        buildJsonArray {
            add(
                buildJsonArray {
                    add(JsonPrimitive(DEFAULT_CAPACITY_CATEGORY_ID))
                    add(JsonNull)
                    add(JsonPrimitive(DEFAULT_PEOPLE_COUNT))
                    add(JsonNull)
                },
            )
        }.toString()

    fun flexibleSearch(anchor: LocalDate): String =
        buildJsonArray {
            add(JsonPrimitive(false))
            add(JsonPrimitive(false))
            add(JsonPrimitive(anchor.toString()))
            add(JsonPrimitive(FLEXIBLE_SEARCH_RANGE_DAYS))
        }.toString()
}
