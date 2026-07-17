package ca.floo.roadtrip.client.recgov

import ca.floo.roadtrip.client.DateStringFormatter
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
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class HttpRecgovAvailabilityClient(
    private val client: HttpClient = defaultClient(),
    private val minGapMs: Long = 1500,
    private val retryDelaysMs: List<Long> = listOf(3_000, 6_000, 12_000),
) : RecGovAvailabilityClient {
    private val log = LoggerFactory.getLogger(HttpRecgovAvailabilityClient::class.java)
    private val mutex = Mutex()

    @Volatile private var lastCallAt: Long = 0

    override suspend fun fetchMonth(
        campgroundId: String,
        monthStart: String,
    ): Map<String, Campsite> {
        val monthLabel = DateStringFormatter.month(monthStart)
        val isoMonth = URLEncoder.encode("${monthStart}T00:00:00.000Z", StandardCharsets.UTF_8)
        val url = "$AVAIL_BASE/$campgroundId/month?start_date=$isoMonth"
        for ((attempt, delayMs) in (listOf(0L) + retryDelaysMs).withIndex()) {
            mutex.withLock {
                val gap = System.currentTimeMillis() - lastCallAt
                if (gap < minGapMs) delay(minGapMs - gap)
                lastCallAt = System.currentTimeMillis()
            }
            log.info("recgov GET availability campground={} month={} attempt={}", campgroundId, monthLabel, attempt + 1)
            val resp = client.get(url)
            if (resp.status == HttpStatusCode.TooManyRequests) {
                if (attempt >= retryDelaysMs.size) {
                    throw RuntimeException("rec.gov 429 after ${retryDelaysMs.size} retries on $campgroundId/$monthLabel")
                }
                val wait = retryDelaysMs[attempt]
                log.warn("429 rate limit on {}/{} — retrying in {}s", campgroundId, monthLabel, wait / 1000)
                delay(wait)
                continue
            }
            if (!resp.status.isSuccess()) {
                throw RuntimeException("rec.gov ${resp.status} on $campgroundId/$monthLabel: ${resp.bodyAsText().take(200)}")
            }
            return parseCampsites(resp.bodyAsText())
        }
        return emptyMap()
    }

    override fun close() = client.close()

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
