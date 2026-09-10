package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.api.AvailabilityDayDto
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The day's status per campsite, ascending by id. */
val AvailabilityDayDto.statuses: Map<Long, AvailabilityStatus>
    get() = cells.mapValues { (_, cell) -> cell.status }

/** The campsites bookable online on this day, ascending by id. */
val AvailabilityDayDto.availableIds: List<Long>
    get() =
        cells
            .filterValues { it.status.isOnlineBookable }
            .keys
            .toList()

/** The same read over a serialized day: campsite id → wire status. */
fun JsonObject.cellStatuses(): Map<String, String> =
    getValue("cells").jsonObject.mapValues { (_, cell) ->
        cell.jsonObject
            .getValue("status")
            .jsonPrimitive.content
    }

/** The campsite ids a serialized day reports bookable online. */
fun JsonObject.availableCellIds(): List<String> =
    cellStatuses()
        .filterValues { it == AvailabilityStatus.AVAILABLE.wireValue }
        .keys
        .toList()
