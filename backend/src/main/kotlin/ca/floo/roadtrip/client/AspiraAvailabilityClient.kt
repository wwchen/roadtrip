package ca.floo.roadtrip.client

import ca.floo.roadtrip.models.aspira.AspiraStatus
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
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

/**
 * Reads availability from Aspira NextGen's public `/api/availability/map`
 * endpoint. Same vendor as the deeplink builder ([web/aspira.js], RFC 0006);
 * powers reservation.pc.gc.ca, washington.goingtocamp.com, discovercamping.ca.
 *
 * Wire shape (verified by manual probe 2026-06-07):
 *
 *   GET /api/availability/map
 *     ?mapId={int}
 *     &bookingCategoryId=0
 *     &startDate=YYYY-MM-DD
 *     &endDate=YYYY-MM-DD
 *     &isReserving=true
 *     &getDailyAvailability=true     <-- per-day breakdown
 *     &partySize=1
 *     &equipmentCategoryId=-32768    <-- "any equipment" sentinel
 *     &subEquipmentCategoryId=-32768
 *
 *   Response:
 *     { "mapId": -2147483630,
 *       "mapAvailabilities": [6,6,0,0,0],         // park-level rollup, one per day
 *       "resourceAvailabilities": {},              // unused for park-level queries
 *       "mapLinkAvailabilities": {                 // each sub-area ("loop"), per-day
 *         "-2147483629": [1,1,0,1,0],
 *         ...
 *       }
 *     }
 *
 * Status codes (observed across multiple parks; documented in [AspiraStatus]):
 *   1=available, 3=partial, 5=closed, 6=mostly-booked, 7=mixed/some-avail, 0=no-data
 *
 * Azure WAF gates aggressive use — a 30-day query for one park is fine, but
 * looping 50 parks back-to-back triggers a CAPTCHA challenge. The mutex below
 * serializes outbound requests to ~1/sec so a hot drawer flow can't trip it.
 *
 * We use Java's built-in HttpClient instead of Ktor's CIO because Ktor's
 * HttpPlainText plugin auto-adds `Accept-Charset: UTF-8` and Aspira's WAF
 * rejects requests carrying that header (real browsers don't send it).
 */
class AspiraAvailabilityClient(
    private val client: HttpClient = defaultClient(),
    private val throttleMs: Long = 1_500,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Single global mutex: one in-flight Aspira call at a time. Aspira's WAF
    // is host-side (Azure App Gateway), and the threat is volume-from-our-IP.
    // Per-host or per-mapId mutexes wouldn't help — same IP, same WAF rules.
    private val mutex = Mutex()
    private var lastFetchAtMs = 0L

    suspend fun fetch(
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
                    "&bookingCategoryId=0" +
                    "&startDate=$startDate" +
                    "&endDate=$endDate" +
                    "&isReserving=true" +
                    "&getDailyAvailability=true" +
                    "&partySize=1" +
                    "&equipmentCategoryId=-32768" +
                    "&subEquipmentCategoryId=-32768"
            log.debug("aspira GET {}", url)
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

    private fun parse(
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
        return AspiraAvailability(
            mapId = mapId,
            parkRollup = map,
            byMapLink = sub,
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

class AspiraException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)

/**
 * One response from Aspira's availability endpoint. `parkRollup` is the
 * `mapAvailabilities` array (one status per day, park-wide). `byMapLink` keys
 * each sub-area's daily-status array by its `childMapId` (string for JSON
 * compatibility — Aspira returns negative ints as object keys).
 */
data class AspiraAvailability(
    val mapId: Int,
    val parkRollup: List<Int>,
    val byMapLink: Map<String, List<Int>>,
)
