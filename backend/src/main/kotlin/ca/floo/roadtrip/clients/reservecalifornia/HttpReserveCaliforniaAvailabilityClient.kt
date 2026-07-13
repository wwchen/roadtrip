package ca.floo.roadtrip.clients.reservecalifornia

import ca.floo.roadtrip.clients.DateStringFormatter
import ca.floo.roadtrip.exceptions.ReserveCaliforniaException
import ca.floo.roadtrip.models.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class HttpReserveCaliforniaAvailabilityClient(
    private val rdrBaseUrl: String = "https://california-rdr.prod.cali.rd12.recreation-management.tylerapp.com/rdr",
    private val client: HttpClient = defaultClient(),
) : ReserveCaliforniaAvailabilityClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun fetchGrid(
        facilityId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        minDate: LocalDate,
        maxDate: LocalDate,
    ): ReserveCaliforniaGridAvailability {
        val observedAt = Instant.now()
        val body = gridRequestBody(facilityId, startDate, endDate, minDate, maxDate)
        log.info(
            "reservecalifornia POST availability facilityId={} startDate={} endDate={} minDate={} maxDate={}",
            facilityId,
            DateStringFormatter.date(startDate),
            DateStringFormatter.date(endDate),
            DateStringFormatter.date(minDate),
            DateStringFormatter.date(maxDate),
        )
        val payload = postJson("$rdrBaseUrl/search/grid", body)
        return ReserveCaliforniaGridParser.parse(payload, observedAt)
    }

    private fun gridRequestBody(
        facilityId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        minDate: LocalDate,
        maxDate: LocalDate,
    ): String =
        buildJsonObject {
            put("FacilityId", facilityId)
            put("UnitSort", "availability")
            put("StartDate", startDate.toString())
            put("EndDate", endDate.toString())
            put("InSeasonOnly", true)
            put("WebOnly", true)
            put("MaxDate", "${maxDate}T00:00:00")
            put("MinDate", "${minDate}T00:00:00")
            put("IsADA", false)
            put("RestrictADA", false)
            put("UnitCategoryId", 0)
            put("SleepingUnitId", 0)
            put("MinVehicleLength", 0)
            put("UnitTypesGroupIds", JsonArray(emptyList()))
            put("AmenityIds", JsonArray(emptyList()))
            put("CustomerId", 0)
            put("customerClassificationId", 0)
        }.toString()

    private suspend fun postJson(
        url: String,
        body: String,
    ): JsonObject {
        val req =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("tenantId", TENANT_ID)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val resp =
            try {
                client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: Exception) {
                throw ReserveCaliforniaException("reservecalifornia request failed: ${e.message}", httpStatus = null)
            }
        if (resp.statusCode() !in 200..299) {
            throw ReserveCaliforniaException("reservecalifornia HTTP ${resp.statusCode()} for $url", resp.statusCode())
        }
        return Json.parseToJsonElement(resp.body()).jsonObject
    }

    companion object {
        private const val TENANT_ID = "cali"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun defaultClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
    }
}
