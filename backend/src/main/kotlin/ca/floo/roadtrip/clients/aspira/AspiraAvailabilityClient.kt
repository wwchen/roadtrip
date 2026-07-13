package ca.floo.roadtrip.clients.aspira
import ca.floo.roadtrip.models.metadata.aspira.AspiraResourceAvailability
import ca.floo.roadtrip.models.metadata.aspira.AspiraStatus
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * Aspira availability + occupancy fetch surface. The HTTP-backed
 * implementation ([HttpAspiraAvailabilityClient]) reads Aspira NextGen's
 * public `/api/availability/map` endpoint. Same vendor as the deeplink
 * builder ([web/aspira.js], RFC 0006); powers reservation.pc.gc.ca,
 * washington.goingtocamp.com, discovercamping.ca. Tests pass fakes.
 *
 * Wire shape (verified by manual probe 2026-06-07):
 *
 *   GET /api/availability/map
 *     ?mapId={int}
 *     &bookingCategoryId={AspiraSearchDefaults.BOOKING_CATEGORY_ID}
 *     &startDate=YYYY-MM-DD
 *     &endDate=YYYY-MM-DD
 *     &isReserving=true
 *     &getDailyAvailability=true     <-- per-day breakdown
 *     &partySize={AspiraSearchDefaults.DEFAULT_PEOPLE_COUNT}
 *     &equipmentCategoryId={AspiraSearchDefaults.ANY_EQUIPMENT_CATEGORY_ID}
 *     &subEquipmentCategoryId={AspiraSearchDefaults.ANY_SUB_EQUIPMENT_CATEGORY_ID}
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
 * Map status codes (observed across multiple parks; documented in [AspiraStatus]):
 *   1=available, 3=partial, 5=closed, 6=mostly-booked, 7=mixed/some-avail, 0=no-data
 * Resource rows use a separate code family, documented in [AspiraResourceAvailability].
 *
 * Azure WAF gates aggressive use — a 30-day query for one park is fine, but
 * looping 50 parks back-to-back triggers a CAPTCHA challenge. The mutex below
 * serializes outbound requests to ~1/sec so a hot drawer flow can't trip it.
 *
 * We use Java's built-in HttpClient instead of Ktor's CIO because Ktor's
 * HttpPlainText plugin auto-adds `Accept-Charset: UTF-8` and Aspira's WAF
 * rejects requests carrying that header (real browsers don't send it).
 */
interface AspiraAvailabilityClient : AutoCloseable {
    suspend fun fetch(
        host: String,
        mapId: Int,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AspiraAvailability

    suspend fun fetchOccupancy(
        host: String,
        resourceLocationId: Int,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AspiraOccupancy

    override fun close() {}
}

internal fun queryString(vararg params: Pair<String, String>): String =
    params.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }

internal fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
