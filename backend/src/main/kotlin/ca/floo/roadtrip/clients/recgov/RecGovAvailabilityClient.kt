package ca.floo.roadtrip.clients.recgov
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * rec.gov availability fetch surface. The HTTP-backed implementation
 * ([HttpRecgovAvailabilityClient]) hits the monthly availability endpoint with a
 * global throttle and 429 backoff. Mirrors poller.js verbatim: 1.5s minimum
 * gap between calls, 3s/6s/12s retries on 429. Tests pass fakes.
 */
interface RecGovAvailabilityClient : AutoCloseable {
    suspend fun fetchMonth(
        campgroundId: String,
        monthStart: String,
    ): Map<String, Campsite>

    override fun close() {}
}

internal fun parseCampsites(body: String): Map<String, Campsite> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
    val campsites = root["campsites"] as? JsonObject ?: return emptyMap()
    val out = mutableMapOf<String, Campsite>()
    for ((id, element) in campsites) {
        val obj = element as? JsonObject ?: continue
        val avail =
            (obj["availabilities"] as? JsonObject)
                ?.mapNotNull { (date, value) ->
                    val status = (value as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
                    date to status
                }?.toMap()
                ?: emptyMap()
        val equip =
            (obj["equipment_types"] as? kotlinx.serialization.json.JsonArray)
                ?.map { (it as JsonPrimitive).content } ?: emptyList()
        out[id] =
            Campsite(
                id = id,
                site = (obj["site"] as? JsonPrimitive)?.contentOrNull(),
                loop = (obj["loop"] as? JsonPrimitive)?.contentOrNull(),
                campsiteType = (obj["campsite_type"] as? JsonPrimitive)?.contentOrNull(),
                maxNumPeople = (obj["max_num_people"] as? JsonPrimitive)?.intOrNull(),
                equipmentTypes = equip,
                availabilities = avail,
            )
    }
    return out
}

private fun JsonPrimitive.contentOrNull(): String? =
    if (this.isString) {
        content
    } else if (content == "null") {
        null
    } else {
        content
    }

private fun JsonPrimitive.intOrNull(): Int? = content.toIntOrNull()

internal fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
