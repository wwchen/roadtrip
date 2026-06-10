package ca.floo.campsite.recgov.booker.poller

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Hits rec.gov's monthly availability endpoint with a global throttle and 429 backoff.
 *
 * Mirrors poller.js verbatim: 1.5s minimum gap between calls, 3s/6s/12s retries on 429.
 * The throttle is global — module-level in JS, instance-level mutex here.
 */
class AvailabilityClient(
    private val client: HttpClient = defaultClient(),
    private val minGapMs: Long = 1500,
    private val retryDelaysMs: List<Long> = listOf(3_000, 6_000, 12_000),
) {
    private val log = LoggerFactory.getLogger(AvailabilityClient::class.java)
    private val mutex = Mutex()

    @Volatile private var lastCallAt: Long = 0

    suspend fun fetchMonth(
        campgroundId: String,
        monthStart: String,
    ): Map<String, Campsite> {
        val isoMonth = URLEncoder.encode("${monthStart}T00:00:00.000Z", StandardCharsets.UTF_8)
        val url = "$AVAIL_BASE/$campgroundId/month?start_date=$isoMonth"
        log.info("Poller: GET availability {}/{}", campgroundId, monthStart)
        for ((attempt, delayMs) in (listOf(0L) + retryDelaysMs).withIndex()) {
            mutex.withLock {
                val gap = System.currentTimeMillis() - lastCallAt
                if (gap < minGapMs) delay(minGapMs - gap)
                lastCallAt = System.currentTimeMillis()
            }
            val resp = client.get(url)
            if (resp.status == HttpStatusCode.TooManyRequests) {
                if (attempt >= retryDelaysMs.size) {
                    throw RuntimeException("rec.gov 429 after ${retryDelaysMs.size} retries on $campgroundId/$monthStart")
                }
                val wait = retryDelaysMs[attempt]
                log.warn("429 rate limit on {}/{} — retrying in {}s", campgroundId, monthStart, wait / 1000)
                delay(wait)
                continue
            }
            if (!resp.status.isSuccess()) {
                throw RuntimeException("rec.gov ${resp.status} on $campgroundId/$monthStart: ${resp.bodyAsText().take(200)}")
            }
            return parseCampsites(resp.bodyAsText())
        }
        return emptyMap()
    }

    fun close() = client.close()

    companion object {
        const val AVAIL_BASE = "https://www.recreation.gov/api/camps/availability/campground"
        private val UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun defaultClient(): HttpClient =
            HttpClient(CIO) {
                engine { requestTimeout = 15_000 }
                defaultRequest {
                    header("User-Agent", UA)
                    header("Accept", "application/json")
                    header("Referer", "https://www.recreation.gov/")
                }
                install(HttpRequestRetry) {
                    retryOnExceptionIf(maxRetries = 2) { _, cause -> cause !is io.ktor.client.plugins.HttpRequestTimeoutException }
                    exponentialDelay()
                }
                expectSuccess = false
            }
    }
}

@Serializable
data class Campsite(
    val id: String,
    val site: String?,
    val loop: String?,
    val campsiteType: String?,
    val maxNumPeople: Int?,
    val equipmentTypes: List<String>,
    val availabilities: Map<String, String>,
)

private fun parseCampsites(body: String): Map<String, Campsite> {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
    val campsites = root["campsites"] as? JsonObject ?: return emptyMap()
    val out = mutableMapOf<String, Campsite>()
    for ((id, element) in campsites) {
        val obj = element as? JsonObject ?: continue
        val avail = (obj["availabilities"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
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
    if (this is JsonPrimitive &&
        this.isString
    ) {
        content
    } else if (content == "null") {
        null
    } else {
        content
    }

private fun JsonPrimitive.intOrNull(): Int? = content.toIntOrNull()

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
