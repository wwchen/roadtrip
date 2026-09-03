package ca.floo.roadtrip.client.recgov

import ca.floo.roadtrip.client.DateStringFormatter
import ca.floo.roadtrip.client.VendorHttpDefaults
import ca.floo.roadtrip.support.RecGovException
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
    private val httpClient: HttpClient = defaultClient(),
    private val minGapMs: Long = DEFAULT_MIN_GAP_MS,
    private val availBaseUrl: String = AVAIL_BASE,
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
        val url = "${availBaseUrl.trimEnd('/')}/$campgroundId/month?start_date=$isoMonth"
        mutex.withLock {
            val gap = System.currentTimeMillis() - lastCallAt
            if (gap < minGapMs) delay(minGapMs - gap)
            lastCallAt = System.currentTimeMillis()
        }
        log.info("recgov GET availability campground={} month={}", campgroundId, monthLabel)
        val resp = httpClient.get(url)
        if (resp.status == HttpStatusCode.TooManyRequests) {
            // Surfaced, not slept on. A blocking ladder here held the calling
            // poll hostage for 21s and hid the rate limit from
            // FailoverAvailabilityFetcher, which would rather cool rec.gov down
            // and try the next candidate immediately.
            log.warn("recgov 429 rate limit on {}/{}", campgroundId, monthLabel)
            throw RecGovException(
                "rec.gov 429 rate limit on $campgroundId/$monthLabel",
                httpStatus = HttpStatusCode.TooManyRequests.value,
            )
        }
        if (!resp.status.isSuccess()) {
            throw RecGovException(
                "rec.gov ${resp.status} on $campgroundId/$monthLabel: ${resp.bodyAsText().take(ERROR_BODY_EXCERPT_CHARS)}",
                httpStatus = resp.status.value,
            )
        }
        return parseCampsites(resp.bodyAsText())
    }

    override fun close() = httpClient.close()

    companion object {
        const val AVAIL_BASE = "https://www.recreation.gov/api/camps/availability/campground"

        /** Floor on the gap between outbound calls; rec.gov 429s on bursts. */
        private const val DEFAULT_MIN_GAP_MS = 1500L

        /** Error bodies go in an exception message — enough to identify the
         *  failure, not enough to dump a page of HTML into the logs. */
        private const val ERROR_BODY_EXCERPT_CHARS = 200

        /** Ktor's CIO engine wants millis; the ceiling itself is the shared one. */
        private val requestTimeoutMs = VendorHttpDefaults.requestTimeout.toMillis()
        private const val MAX_TRANSPORT_RETRIES = 2
        private val userAgent =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun defaultClient(): HttpClient =
            HttpClient(CIO) {
                engine { requestTimeout = requestTimeoutMs }
                defaultRequest {
                    header("User-Agent", userAgent)
                    header("Accept", "application/json")
                    header("Referer", "https://www.recreation.gov/")
                }
                install(HttpRequestRetry) {
                    retryOnExceptionIf(maxRetries = MAX_TRANSPORT_RETRIES) { _, cause ->
                        cause !is io.ktor.client.plugins.HttpRequestTimeoutException
                    }
                    exponentialDelay()
                }
                expectSuccess = false
            }
    }
}
