package ca.floo.campsite.recgov.booker.events

import ca.floo.campsite.recgov.booker.domain.Match
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun matchFoundEventData(m: Match): String =
    Json.encodeToString(
        MatchFoundEventDto(
            id = m.id,
            alertId = m.alertId,
            campgroundId = m.campgroundId,
            campsiteId = m.campsiteId,
            site = m.campsiteSite ?: "",
            loop = m.campsiteLoop ?: "",
            campsiteType = m.campsiteType ?: "",
            firstDate = m.firstDate,
            nights = m.nights,
            availableDates = m.availableDates,
            foundAt = m.foundAt,
            campgroundName = m.campgroundName ?: "",
        ),
    )

@Serializable
private data class MatchFoundEventDto(
    val id: Long,
    val alertId: Long,
    val campgroundId: String,
    val campsiteId: String,
    val site: String,
    val loop: String,
    val campsiteType: String,
    val firstDate: String,
    val nights: Int,
    val availableDates: List<String>,
    val foundAt: String,
    val campgroundName: String,
)
