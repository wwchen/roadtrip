package ca.floo.roadtrip.client.campflare

import ca.floo.roadtrip.client.DateStringFormatter
import ca.floo.roadtrip.client.VendorHttpDefaults
import ca.floo.roadtrip.client.VendorHttpTransport
import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.support.CampflareException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Instant
import java.time.LocalDate

class HttpCampflareAvailabilityClient(
    private val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    private val apiKey: String? = null,
    private val httpClient: HttpClient = defaultClient(),
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
        val key =
            apiKey?.takeIf { it.isNotBlank() }
                ?: throw CampflareException("missing roadtrip.campflare.api-key", httpStatus = null)
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
                .timeout(VendorHttpDefaults.requestTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Authorization", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val body =
            VendorHttpTransport.send(httpClient, req, "campflare", url) { message, status, cause ->
                CampflareException(message, httpStatus = status, cause = cause)
            }
        return Json.parseToJsonElement(body).jsonObject
    }

    companion object {
        private const val MAX_BULK_CAMPGROUNDS = 25
        private const val DEFAULT_API_BASE_URL = "https://api.campflare.com/v2"
        private const val USER_AGENT = "roadtrip-campflare-availability/1.0"

        fun defaultClient(): HttpClient = VendorHttpTransport.client()
    }
}
