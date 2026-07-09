package ca.floo.roadtrip.clients.campflare

import ca.floo.roadtrip.clients.DateStringFormatter
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

fun interface CampflareAvailabilityClient : AutoCloseable {
    suspend fun fetchAvailability(
        campgroundIds: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): CampflareAvailability

    override fun close() {}
}

class HttpCampflareAvailabilityClient(
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val apiKey: String? = null,
    private val client: HttpClient = defaultClient(),
) : CampflareAvailabilityClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun fetchAvailability(
        campgroundIds: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): CampflareAvailability {
        if (campgroundIds.isEmpty()) {
            return CampflareAvailability(campgrounds = emptyMap(), observedAt = Instant.now())
        }
        require(campgroundIds.size <= MAX_BULK_CAMPGROUNDS) {
            "Campflare bulk availability accepts at most $MAX_BULK_CAMPGROUNDS campground ids"
        }
        val key = apiKey?.takeIf { it.isNotBlank() } ?: throw CampflareException("missing CAMPFLARE_API_KEY", httpStatus = null)
        log.info(
            "campflare POST bulk availability campgroundCount={} startDate={} endDate={}",
            campgroundIds.size,
            DateStringFormatter.date(startDate),
            DateStringFormatter.date(endDate),
        )
        val observedAt = Instant.now()
        val payload =
            postJson(
                url = "${apiBaseUrl.trimEnd('/')}/campgrounds/availability",
                apiKey = key,
                body = bulkRequestBody(campgroundIds, startDate, endDate),
            )
        return CampflareAvailabilityParser.parse(payload, observedAt)
    }

    private fun bulkRequestBody(
        campgroundIds: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): String =
        buildJsonObject {
            put("campground_ids", JsonArray(campgroundIds.map(::JsonPrimitive)))
            put("start_date", startDate.toString())
            put("end_date", endDate.toString())
        }.toString()

    private suspend fun postJson(
        url: String,
        apiKey: String,
        body: String,
    ): JsonObject {
        val req =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val resp =
            try {
                client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: Exception) {
                throw CampflareException("campflare request failed: ${e.message}", httpStatus = null)
            }
        if (resp.statusCode() !in 200..299) {
            throw CampflareException("campflare HTTP ${resp.statusCode()} for $url", resp.statusCode())
        }
        return Json.parseToJsonElement(resp.body()).jsonObject
    }

    companion object {
        private const val MAX_BULK_CAMPGROUNDS = 25
        private const val DEFAULT_API_BASE_URL = "https://api.campflare.com/v2"
        private const val USER_AGENT = "roadtrip-campflare-availability/1.0"
        private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)

        fun defaultClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }
}

object CampflareAvailabilityParser {
    fun parse(
        payload: JsonObject,
        observedAt: Instant,
    ): CampflareAvailability {
        val campgrounds =
            payload["campgrounds"]
                ?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
                .mapNotNull(::parseCampground)
                .associateBy { it.campgroundId }
        return CampflareAvailability(campgrounds = campgrounds, observedAt = observedAt)
    }

    private fun parseCampground(raw: JsonObject): CampflareCampgroundAvailability? {
        val campgroundId = raw["campground_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val campsiteAvailability =
            raw["campsite_availability"]
                ?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
                .mapNotNull(::parseCampsite)
        return CampflareCampgroundAvailability(
            campgroundId = campgroundId,
            campsiteAvailability = campsiteAvailability,
        )
    }

    private fun parseCampsite(raw: JsonObject): CampflareCampsiteAvailability? {
        val campsiteId = raw["campsite_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val availability =
            raw["availability"]
                ?.jsonObject
                ?.mapNotNull { (date, status) ->
                    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
                    parsedDate to classify(status.jsonPrimitive.contentOrNull)
                }.orEmpty()
                .toMap()
        return CampflareCampsiteAvailability(campsiteId = campsiteId, availability = availability)
    }

    private fun classify(raw: String?): AvailabilityStatus =
        when (raw?.trim()?.lowercase()) {
            "available" -> AvailabilityStatus.AVAILABLE
            "reserved" -> AvailabilityStatus.RESERVED
            "closed" -> AvailabilityStatus.CLOSED
            "first-come-first-serve", "first_come", "first come first serve" -> AvailabilityStatus.FIRST_COME
            "not-yet-released", "unknown" -> AvailabilityStatus.UNKNOWN
            else -> AvailabilityStatus.UNKNOWN
        }
}

data class CampflareAvailability(
    val campgrounds: Map<String, CampflareCampgroundAvailability>,
    val observedAt: Instant,
)

data class CampflareCampgroundAvailability(
    val campgroundId: String,
    val campsiteAvailability: List<CampflareCampsiteAvailability>,
)

data class CampflareCampsiteAvailability(
    val campsiteId: String,
    val availability: Map<LocalDate, AvailabilityStatus>,
)

class CampflareException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
