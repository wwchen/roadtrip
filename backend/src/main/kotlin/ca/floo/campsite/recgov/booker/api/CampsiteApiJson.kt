package ca.floo.campsite.recgov.booker.api

import ca.floo.campsite.recgov.booker.domain.Alert
import ca.floo.campsite.recgov.booker.domain.Match
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun matchEnvelope(m: Match): String = campsiteApiJson.encodeToString(matchEnvelopeDto(m))

internal fun matchEnvelopeDto(m: Match): MatchEnvelopeDto = m.toEnvelopeDto()

internal fun matchListDtos(matches: List<Match>): List<MatchListItemDto> = matches.map { it.toListItemDto() }

internal fun alertDtos(alerts: List<Alert>): List<AlertDto> = alerts.map { it.toDto() }

@OptIn(ExperimentalSerializationApi::class)
internal val campsiteApiJson: Json =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

internal suspend inline fun <reified T> ApplicationCall.respondJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(campsiteApiJson.encodeToString(value), ContentType.Application.Json, status)
}

internal suspend inline fun <reified T> ApplicationCall.receiveCampsiteJson(): T =
    campsiteApiJson.decodeFromString(
        receiveText().ifBlank { "{}" },
    )

internal suspend fun ApplicationCall.respondJsonElement(
    value: JsonElement,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(value.toString(), ContentType.Application.Json, status)
}

@Serializable
internal data class OkDto(
    val ok: Boolean = true,
)

@Serializable
internal data class ErrorDto(
    val error: String,
)

@Serializable
internal data class AlertCreateRequestDto(
    @SerialName("campground_id") val campgroundId: String? = null,
    @SerialName("campground_name") val campgroundName: String? = null,
    @SerialName("parent_name") val parentName: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("min_nights") val minNights: Int = 1,
    @SerialName("campsite_types") val campsiteTypes: List<String> = emptyList(),
    @SerialName("equipment_types") val equipmentTypes: List<String> = emptyList(),
    @SerialName("max_people") val maxPeople: Int? = null,
    @SerialName("specific_sites") val specificSites: List<String> = emptyList(),
    @SerialName("notify_slack") val notifySlack: Boolean = true,
    @SerialName("auto_cart") val autoCart: Boolean = false,
    @SerialName("stop_after_match") val stopAfterMatch: Boolean = true,
    val notes: String? = null,
)

@Serializable
internal data class AlertPatchRequestDto(
    val status: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("min_nights") val minNights: Int? = null,
    @SerialName("max_people") val maxPeople: Int? = null,
    @SerialName("campsite_types") val campsiteTypes: List<String>? = null,
    @SerialName("equipment_types") val equipmentTypes: List<String>? = null,
    @SerialName("specific_sites") val specificSites: List<String>? = null,
    @SerialName("notify_slack") val notifySlack: Boolean? = null,
    @SerialName("auto_cart") val autoCart: Boolean? = null,
    @SerialName("stop_after_match") val stopAfterMatch: Boolean? = null,
)

@Serializable
internal data class AlertCreatedDto(
    val id: Long,
)

@Serializable
internal data class AlertDto(
    val id: Long,
    @SerialName("campground_id") val campgroundId: String,
    @SerialName("campground_name") val campgroundName: String,
    @SerialName("parent_name") val parentName: String,
    @SerialName("parent_id") val parentId: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("min_nights") val minNights: Int,
    @SerialName("campsite_types") val campsiteTypes: List<String>,
    @SerialName("equipment_types") val equipmentTypes: List<String>,
    @SerialName("max_people") val maxPeople: Int,
    @SerialName("specific_sites") val specificSites: List<String>,
    @SerialName("notify_slack") val notifySlack: Boolean,
    @SerialName("auto_cart") val autoCart: Boolean,
    @SerialName("stop_after_match") val stopAfterMatch: Boolean,
    val status: String,
    @SerialName("last_checked") val lastChecked: String,
    @SerialName("last_match") val lastMatch: String,
    val notes: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
internal data class SessionProbeDto(
    val loggedIn: Boolean,
    val count: Int? = null,
    val hasBearer: Boolean? = null,
    val tokenExpires: String? = null,
    val tokenExpired: Boolean? = null,
    val refreshed: Boolean? = null,
    val error: String? = null,
)

@Serializable
internal data class SessionRefreshDto(
    val ok: Boolean = true,
    val expires: String? = null,
)

@Serializable
internal data class CartOpenDto(
    val ok: Boolean = true,
    val url: String,
)

@Serializable
internal data class CartQueuedDto(
    val ok: Boolean = true,
    val queued: Boolean = true,
    @SerialName("cart_added") val cartAdded: Boolean = false,
)

@Serializable
internal data class WorkNextDto(
    val match: MatchEnvelopeDto? = null,
)

@Serializable
internal data class ClaimDto(
    val ok: Boolean = true,
    val leaseExpires: String,
)

@Serializable
internal data class ConflictDto(
    val ok: Boolean = false,
    val reason: String,
)

@Serializable
internal data class CompanionHeartbeatRequestDto(
    @SerialName("companion_id") val companionId: String? = null,
)

@Serializable
internal data class CompanionStatusDto(
    val companions: List<CompanionStatusItemDto>,
)

@Serializable
internal data class CompanionStatusItemDto(
    val id: String,
    val lastSeen: String,
    val offline: Boolean,
)

@Serializable
internal data class DebugSynthMatchRequestDto(
    val campgroundId: String? = null,
    val campsiteId: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
)

@Serializable
internal data class DebugSynthMatchDto(
    val ok: Boolean = true,
    val id: Long,
)

@Serializable
internal data class MatchAvailabilityDto(
    val status: String,
)

@Serializable
internal data class MatchEnvelopeDto(
    val id: Long,
    val alertId: Long,
    val campgroundId: String,
    val campsiteId: String,
    val site: String,
    val loop: String,
    val campsiteType: String,
    val firstDate: String,
    val nights: Int,
    val foundAt: String,
    val startDate: String,
    val endDate: String,
    val availableDates: List<String>,
    val campgroundName: String,
    val notified: Boolean,
    val claimedBy: String,
    val leaseExpires: String,
    val cartAdded: Boolean,
    val resultAt: String,
)

@Serializable
internal data class MatchListItemDto(
    val id: Long,
    @SerialName("alert_id") val alertId: Long,
    @SerialName("campground_id") val campgroundId: String,
    @SerialName("campsite_id") val campsiteId: String,
    @SerialName("campsite_site") val campsiteSite: String,
    @SerialName("campsite_loop") val campsiteLoop: String,
    @SerialName("campsite_type") val campsiteType: String,
    @SerialName("first_date") val firstDate: String,
    val nights: Int,
    @SerialName("available_dates") val availableDates: List<String>,
    @SerialName("found_at") val foundAt: String,
    val notified: Boolean,
    @SerialName("cart_added") val cartAdded: Boolean,
    @SerialName("campground_name") val campgroundName: String,
    @SerialName("alert_start") val alertStart: String,
    @SerialName("alert_end") val alertEnd: String,
)

private fun Alert.toDto(): AlertDto =
    AlertDto(
        id = id,
        campgroundId = campgroundId,
        campgroundName = campgroundName,
        parentName = parentName ?: "",
        parentId = parentId ?: "",
        startDate = startDate,
        endDate = endDate,
        minNights = minNights,
        campsiteTypes = campsiteTypes,
        equipmentTypes = equipmentTypes,
        maxPeople = maxPeople ?: 0,
        specificSites = specificSites,
        notifySlack = notifySlack,
        autoCart = autoCart,
        stopAfterMatch = stopAfterMatch,
        status = status,
        lastChecked = lastChecked ?: "",
        lastMatch = lastMatch ?: "",
        notes = notes ?: "",
        createdAt = createdAt,
    )

private fun Match.toEnvelopeDto(): MatchEnvelopeDto =
    MatchEnvelopeDto(
        id = id,
        alertId = alertId,
        campgroundId = campgroundId,
        campsiteId = campsiteId,
        site = campsiteSite ?: "",
        loop = campsiteLoop ?: "",
        campsiteType = campsiteType ?: "",
        firstDate = firstDate,
        nights = nights,
        foundAt = foundAt,
        startDate = firstDate,
        endDate = firstDate,
        availableDates = availableDates,
        campgroundName = campgroundName ?: "",
        notified = notified,
        claimedBy = claimedBy ?: "",
        leaseExpires = leaseExpires ?: "",
        cartAdded = cartAdded ?: false,
        resultAt = resultAt ?: "",
    )

private fun Match.toListItemDto(): MatchListItemDto =
    MatchListItemDto(
        id = id,
        alertId = alertId,
        campgroundId = campgroundId,
        campsiteId = campsiteId,
        campsiteSite = campsiteSite ?: "",
        campsiteLoop = campsiteLoop ?: "",
        campsiteType = campsiteType ?: "",
        firstDate = firstDate,
        nights = nights,
        availableDates = availableDates,
        foundAt = foundAt,
        notified = notified,
        cartAdded = cartAdded ?: false,
        campgroundName = campgroundName ?: "",
        alertStart = alertStart ?: "",
        alertEnd = alertEnd ?: "",
    )

internal class JsonView(
    val obj: JsonObject,
) {
    fun string(key: String): String? = (obj[key] as? JsonPrimitive)?.let { if (it.isString) it.content else null }

    fun bool(key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    fun int(key: String): Int? = (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()

    fun long(key: String): Long? = (obj[key] as? JsonPrimitive)?.content?.toLongOrNull()

    fun array(key: String): JsonArray? = obj[key] as? JsonArray

    fun stringList(key: String): List<String> = (obj[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
}

internal fun parseJson(body: String): JsonView {
    val obj = (Json.parseToJsonElement(body.ifBlank { "{}" }) as? JsonObject) ?: JsonObject(emptyMap())
    return JsonView(obj)
}
