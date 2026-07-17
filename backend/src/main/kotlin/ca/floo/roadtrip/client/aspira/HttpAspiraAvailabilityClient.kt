package ca.floo.roadtrip.client.aspira

import ca.floo.roadtrip.client.DateStringFormatter
import ca.floo.roadtrip.model.metadata.aspira.AspiraResourceAvailability
import ca.floo.roadtrip.model.metadata.aspira.AspiraStatus
import ca.floo.roadtrip.support.AspiraException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate

class HttpAspiraAvailabilityClient(
    private val client: HttpClient = defaultClient(),
    private val throttleMs: Long = 1_500,
) : AspiraAvailabilityClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    // Single global mutex: one in-flight Aspira call at a time. Aspira's WAF
    // is host-side (Azure App Gateway), and the threat is volume-from-our-IP.
    // Per-host or per-mapId mutexes wouldn't help — same IP, same WAF rules.
    private val mutex = Mutex()
    private var lastFetchAtMs = 0L

    override suspend fun fetch(
        host: String,
        mapId: Int,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AspiraAvailability =
        mutex.withLock {
            val sinceLast = System.currentTimeMillis() - lastFetchAtMs
            if (sinceLast < throttleMs) {
                kotlinx.coroutines.delay(throttleMs - sinceLast)
            }
            val url =
                "https://$host/api/availability/map" +
                    "?mapId=$mapId" +
                    "&bookingCategoryId=${AspiraSearchDefaults.BOOKING_CATEGORY_ID}" +
                    "&startDate=$startDate" +
                    "&endDate=$endDate" +
                    "&isReserving=true" +
                    "&getDailyAvailability=true" +
                    "&partySize=${AspiraSearchDefaults.DEFAULT_PEOPLE_COUNT}" +
                    "&equipmentCategoryId=${AspiraSearchDefaults.ANY_EQUIPMENT_CATEGORY_ID}" +
                    "&subEquipmentCategoryId=${AspiraSearchDefaults.ANY_SUB_EQUIPMENT_CATEGORY_ID}"
            val req =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    // Aspira's WAF rejects bare-curl UAs (returns 403). A
                    // browser-shaped UA is the difference between 200 and
                    // immediately tripping the bot challenge.
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Referer", "https://$host/")
                    .GET()
                    .build()
            log.info(
                "aspira GET availability host={} mapId={} startDate={} endDate={}",
                host,
                mapId,
                DateStringFormatter.date(startDate),
                DateStringFormatter.date(endDate),
            )
            val resp =
                try {
                    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
                } catch (e: Exception) {
                    throw AspiraException("aspira request failed: ${e.message}", httpStatus = null)
                }
            lastFetchAtMs = System.currentTimeMillis()
            if (resp.statusCode() != 200) {
                throw AspiraException(
                    "aspira HTTP ${resp.statusCode()} for mapId=$mapId",
                    httpStatus = resp.statusCode(),
                )
            }
            val body = resp.body()
            // WAF challenge bypass detection: Azure WAF returns HTML 200s.
            if (body.startsWith("<")) {
                throw AspiraException("aspira WAF challenge (HTML response)", httpStatus = 503)
            }
            parse(body, mapId)
        }

    override suspend fun fetchOccupancy(
        host: String,
        resourceLocationId: Int,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AspiraOccupancy =
        mutex.withLock {
            val sinceLast = System.currentTimeMillis() - lastFetchAtMs
            if (sinceLast < throttleMs) {
                kotlinx.coroutines.delay(throttleMs - sinceLast)
            }
            val url =
                "https://$host/api/occupancy?" +
                    queryString(
                        "bookingCategoryId" to AspiraSearchDefaults.BOOKING_CATEGORY_ID.toString(),
                        "equipmentCategoryId" to AspiraSearchDefaults.ANY_EQUIPMENT_CATEGORY_ID.toString(),
                        "subEquipmentCategoryId" to AspiraSearchDefaults.ANY_SUB_EQUIPMENT_CATEGORY_ID.toString(),
                        "startDate" to startDate.toString(),
                        "endDate" to endDate.toString(),
                        "boatLength" to AspiraSearchDefaults.DEFAULT_BOAT_LENGTH.toString(),
                        "boatDraft" to AspiraSearchDefaults.DEFAULT_BOAT_DRAFT.toString(),
                        "boatWidth" to AspiraSearchDefaults.DEFAULT_BOAT_WIDTH.toString(),
                        "peopleCapacityCategoryCounts" to AspiraSearchDefaults.occupancyPeopleCapacityCategoryCounts(),
                        "numEquipment" to AspiraSearchDefaults.NO_EQUIPMENT_COUNT.toString(),
                        "resourceLocationId" to resourceLocationId.toString(),
                        "cartUid" to "",
                        "cartTransactionUid" to "",
                        "bookingUid" to "",
                        "groupHoldUid" to "",
                    )
            val req =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Referer", "https://$host/")
                    .GET()
                    .build()
            log.info(
                "aspira GET occupancy host={} resourceLocationId={} startDate={} endDate={}",
                host,
                resourceLocationId,
                DateStringFormatter.date(startDate),
                DateStringFormatter.date(endDate),
            )
            val resp =
                try {
                    client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
                } catch (e: Exception) {
                    throw AspiraException("aspira occupancy request failed: ${e.message}", httpStatus = null)
                }
            lastFetchAtMs = System.currentTimeMillis()
            if (resp.statusCode() != 200) {
                throw AspiraException(
                    "aspira occupancy HTTP ${resp.statusCode()} for resourceLocationId=$resourceLocationId",
                    httpStatus = resp.statusCode(),
                )
            }
            val body = resp.body()
            if (body.startsWith("<")) {
                throw AspiraException("aspira occupancy WAF challenge (HTML response)", httpStatus = 503)
            }
            json.decodeFromString(AspiraOccupancy.serializer(), body)
        }

    internal fun parse(
        body: String,
        mapId: Int,
    ): AspiraAvailability {
        val root = Json.parseToJsonElement(body).jsonObject
        val map =
            root["mapAvailabilities"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.intOrNull ?: AspiraStatus.NO_DATA } ?: emptyList()
        val sub =
            root["mapLinkAvailabilities"]?.jsonObject?.mapValues { (_, v) ->
                v.jsonArray.map { it.jsonPrimitive.intOrNull ?: AspiraStatus.NO_DATA }
            } ?: emptyMap()
        val resources =
            root["resourceAvailabilities"]?.jsonObject?.mapValues { (_, v) ->
                v.jsonArray.map { day ->
                    (day as? JsonObject)
                        ?.get("availability")
                        ?.jsonPrimitive
                        ?.intOrNull
                        ?: AspiraResourceAvailability.UNKNOWN
                }
            } ?: emptyMap()
        return AspiraAvailability(
            mapId = mapId,
            parkRollup = map,
            byMapLink = sub,
            byResource = resources,
        )
    }

    companion object {
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
