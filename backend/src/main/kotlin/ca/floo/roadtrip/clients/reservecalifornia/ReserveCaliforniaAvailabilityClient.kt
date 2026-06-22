package ca.floo.roadtrip.clients.reservecalifornia

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

interface ReserveCaliforniaAvailabilityClient {
    suspend fun fetchGrid(
        facilityId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        minDate: LocalDate,
        maxDate: LocalDate,
    ): ReserveCaliforniaGridAvailability
}

class HttpReserveCaliforniaAvailabilityClient(
    private val rdrBaseUrl: String = "https://california-rdr.prod.cali.rd12.recreation-management.tylerapp.com/rdr",
    private val client: HttpClient = defaultClient(),
) : ReserveCaliforniaAvailabilityClient {
    override suspend fun fetchGrid(
        facilityId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        minDate: LocalDate,
        maxDate: LocalDate,
    ): ReserveCaliforniaGridAvailability {
        val observedAt = Instant.now()
        val body =
            """
            {
              "FacilityId": $facilityId,
              "UnitSort": "availability",
              "StartDate": "$startDate",
              "EndDate": "$endDate",
              "InSeasonOnly": true,
              "WebOnly": true,
              "MaxDate": "${maxDate}T00:00:00",
              "MinDate": "${minDate}T00:00:00",
              "IsADA": false,
              "RestrictADA": false,
              "UnitCategoryId": 0,
              "SleepingUnitId": 0,
              "MinVehicleLength": 0,
              "UnitTypesGroupIds": [],
              "AmenityIds": [],
              "CustomerId": 0,
              "customerClassificationId": 0
            }
            """.trimIndent()
        val payload = postJson("$rdrBaseUrl/search/grid", body)
        return ReserveCaliforniaGridParser.parse(payload, observedAt)
    }

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

object ReserveCaliforniaGridParser {
    fun parse(
        payload: JsonObject,
        observedAt: Instant,
    ): ReserveCaliforniaGridAvailability {
        val facility = payload["Facility"]?.jsonObject ?: JsonObject(emptyMap())
        val facilityId = facility["FacilityId"]?.jsonPrimitive?.longOrNull ?: 0L
        val units = facility["Units"]?.jsonObject ?: JsonObject(emptyMap())
        val statuses = linkedMapOf<String, Map<LocalDate, AvailabilityStatus>>()
        val names = linkedMapOf<String, String?>()

        for ((_, rawUnit) in units) {
            val unit = rawUnit as? JsonObject ?: continue
            val unitId = unit["UnitId"]?.jsonPrimitive?.longOrNull?.toString() ?: continue
            names[unitId] = unit["Name"]?.jsonPrimitive?.contentOrNull
            val isWebViewable = unit["IsWebViewable"]?.jsonPrimitive?.booleanOrNull ?: false
            val allowWebBooking = unit["AllowWebBooking"]?.jsonPrimitive?.booleanOrNull ?: false
            val slices = unit["Slices"]?.jsonObject ?: JsonObject(emptyMap())
            val byDate = linkedMapOf<LocalDate, AvailabilityStatus>()
            for ((sliceKey, rawSlice) in slices) {
                val slice = rawSlice as? JsonObject ?: continue
                val date =
                    slice["Date"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.take(10)
                        ?.let(LocalDate::parse)
                        ?: sliceKey.take(10).let(LocalDate::parse)
                byDate[date] = classify(slice, isWebViewable = isWebViewable, allowWebBooking = allowWebBooking)
            }
            statuses[unitId] = byDate
        }

        return ReserveCaliforniaGridAvailability(
            facilityId = facilityId,
            observedAt = observedAt,
            statuses = statuses,
            unitNames = names,
        )
    }

    private fun classify(
        slice: JsonObject,
        isWebViewable: Boolean,
        allowWebBooking: Boolean,
    ): AvailabilityStatus {
        if (!isWebViewable || !allowWebBooking) return AvailabilityStatus.CLOSED
        val isWalkin = slice["IsWalkin"]?.jsonPrimitive?.booleanOrNull == true
        if (isWalkin) return AvailabilityStatus.FIRST_COME
        val isBlocked = slice["IsBlocked"]?.jsonPrimitive?.booleanOrNull == true
        if (isBlocked) return AvailabilityStatus.CLOSED
        val reservationId = slice["ReservationId"]?.jsonPrimitive?.longOrNull ?: 0L
        if (reservationId != 0L) return AvailabilityStatus.RESERVED
        val isFree = slice["IsFree"]?.jsonPrimitive?.booleanOrNull == true
        val lock = slice["Lock"]
        return if (isFree && (lock == null || lock == JsonNull)) AvailabilityStatus.AVAILABLE else AvailabilityStatus.UNKNOWN
    }
}

data class ReserveCaliforniaGridAvailability(
    val facilityId: Long,
    val observedAt: Instant,
    val statuses: Map<String, Map<LocalDate, AvailabilityStatus>>,
    val unitNames: Map<String, String?> = emptyMap(),
)

class ReserveCaliforniaException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)
