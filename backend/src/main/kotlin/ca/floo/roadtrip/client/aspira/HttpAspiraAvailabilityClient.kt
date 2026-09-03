package ca.floo.roadtrip.client.aspira

import ca.floo.roadtrip.client.DateStringFormatter
import ca.floo.roadtrip.client.VendorHttpDefaults
import ca.floo.roadtrip.client.VendorHttpTransport
import ca.floo.roadtrip.model.metadata.aspira.AspiraStatus
import ca.floo.roadtrip.support.AspiraException
import kotlinx.coroutines.delay
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
import java.time.LocalDate

class HttpAspiraAvailabilityClient(
    private val httpClient: HttpClient = defaultClient(),
    private val throttleMs: Long = DEFAULT_THROTTLE_MS,
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
    ): AspiraAvailability {
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
        log.info(
            "aspira GET availability host={} mapId={} startDate={} endDate={}",
            host,
            mapId,
            DateStringFormatter.date(startDate),
            DateStringFormatter.date(endDate),
        )
        return parse(throttledGet(host, url, label = "availability", target = "mapId=$mapId"), mapId)
    }

    override suspend fun fetchOccupancy(
        host: String,
        resourceLocationId: Int,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AspiraOccupancy {
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
        log.info(
            "aspira GET occupancy host={} resourceLocationId={} startDate={} endDate={}",
            host,
            resourceLocationId,
            DateStringFormatter.date(startDate),
            DateStringFormatter.date(endDate),
        )
        val body = throttledGet(host, url, label = "occupancy", target = "resourceLocationId=$resourceLocationId")
        return json.decodeFromString(AspiraOccupancy.serializer(), body)
    }

    /**
     * The only way this client talks to Aspira: one call at a time, throttled,
     * browser-shaped, with every upstream failure already an [AspiraException].
     * Callers own URL construction and parsing, nothing else — the gate and the
     * WAF checks are the part that must not diverge between endpoints.
     *
     * [label] and [target] name the call in error messages so a failure still
     * says which endpoint and which id it was.
     */
    private suspend fun throttledGet(
        host: String,
        url: String,
        label: String,
        target: String,
    ): String =
        mutex.withLock {
            val sinceLast = System.currentTimeMillis() - lastFetchAtMs
            if (sinceLast < throttleMs) {
                delay(throttleMs - sinceLast)
            }
            val req =
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(VendorHttpDefaults.requestTimeout)
                    // Aspira's WAF rejects bare-curl UAs (returns 403). A
                    // browser-shaped UA is the difference between 200 and
                    // immediately tripping the bot challenge.
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Referer", "https://$host/")
                    .GET()
                    .build()
            val resp =
                try {
                    httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
                } catch (e: Exception) {
                    throw AspiraException(
                        "aspira $label request failed for $target host=$host: ${e.javaClass.name}: ${e.message}",
                        httpStatus = null,
                        cause = e,
                    )
                } finally {
                    // Record the attempt, not the success. A failed call still
                    // cost the WAF a request from our IP, so updating this only
                    // on success meant an outage silently switched the throttle
                    // off and we hammered them with zero gap — which is how a
                    // transient block turns into a persistent one.
                    lastFetchAtMs = System.currentTimeMillis()
                }
            if (resp.statusCode() != HTTP_OK) {
                throw AspiraException(
                    "aspira $label HTTP ${resp.statusCode()} for $target",
                    httpStatus = resp.statusCode(),
                )
            }
            val body = resp.body()
            // WAF challenge bypass detection: Azure WAF returns HTML 200s.
            if (body.startsWith("<")) {
                throw AspiraException("aspira $label WAF challenge (HTML response)", httpStatus = WAF_CHALLENGE_STATUS)
            }
            body
        }

    internal fun parse(
        body: String,
        mapId: Int,
    ): AspiraAvailability {
        val root = Json.parseToJsonElement(body).jsonObject
        val map =
            root["mapAvailabilities"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.intOrNull ?: AspiraStatus.UNKNOWN } ?: emptyList()
        val sub =
            root["mapLinkAvailabilities"]?.jsonObject?.mapValues { (_, v) ->
                v.jsonArray.map { it.jsonPrimitive.intOrNull ?: AspiraStatus.UNKNOWN }
            } ?: emptyMap()
        val resources =
            root["resourceAvailabilities"]?.jsonObject?.mapValues { (_, v) ->
                v.jsonArray.map { day ->
                    (day as? JsonObject)
                        ?.get("availability")
                        ?.jsonPrimitive
                        ?.intOrNull
                        ?: AspiraStatus.UNKNOWN
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
        /** One call per 1.5s: Aspira's WAF scores burst volume from one IP,
         *  and this is the gap a hot drawer flow survived in probing. */
        private const val DEFAULT_THROTTLE_MS = 1_500L
        private const val HTTP_OK = 200

        /** A WAF challenge page is upstream refusing us, so it is reported with
         *  the status the classifier already treats as blocked. */
        private const val WAF_CHALLENGE_STATUS = 503
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun defaultClient(): HttpClient = VendorHttpTransport.client()
    }
}
