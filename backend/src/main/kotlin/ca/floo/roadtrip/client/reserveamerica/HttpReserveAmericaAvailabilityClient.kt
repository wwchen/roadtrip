package ca.floo.roadtrip.client.reserveamerica

import ca.floo.roadtrip.client.DateStringFormatter
import ca.floo.roadtrip.client.VendorHttpDefaults
import ca.floo.roadtrip.client.VendorHttpTransport
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.support.ReserveAmericaException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class HttpReserveAmericaAvailabilityClient(
    private val httpClient: HttpClient = defaultClient(),
) : ReserveAmericaAvailabilityClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val sessionMutex = Mutex()
    private val primedHosts = mutableSetOf<String>()

    override suspend fun fetch(
        host: String,
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ReserveAmericaAvailability {
        primeSession(host)
        val observedAt = Instant.now()
        val statuses = linkedMapOf<String, MutableMap<LocalDate, AvailabilityStatus>>()
        var cursor = startDate
        while (cursor.isBefore(endDate)) {
            val pageEnd = minOf(cursor.plusDays(MATRIX_DAYS.toLong()), endDate)
            fetchWindow(host, contractCode, parkId, cursor, pageEnd, statuses)
            cursor = pageEnd
        }
        return ReserveAmericaAvailability(
            contractCode = contractCode,
            parkId = parkId,
            startDate = startDate,
            endDate = endDate,
            observedAt = observedAt,
            statuses = statuses.mapValues { (_, byDate) -> byDate.toMap() },
        )
    }

    private suspend fun fetchWindow(
        host: String,
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        out: MutableMap<String, MutableMap<LocalDate, AvailabilityStatus>>,
    ) {
        var startIdx = 0
        var totalSites: Int? = null
        while (totalSites == null || startIdx < totalSites) {
            log.info(
                "reserveamerica GET availability host={} contractCode={} parkId={} startDate={} endDate={} startIdx={}",
                host,
                contractCode,
                parkId,
                DateStringFormatter.date(startDate),
                DateStringFormatter.date(endDate),
                startIdx,
            )
            val html = get(host, matrixUrl(host, contractCode, parkId, startDate, startIdx))
            val page = ReserveAmericaAvailabilityParser.parse(html, startDate, endDate)
            merge(out, page.statuses)
            if (totalSites == null) {
                totalSites = page.totalSites ?: page.statuses.size
            }
            val nextStart = startIdx + PAGE_SIZE
            if (page.statuses.isEmpty() || nextStart <= startIdx) break
            startIdx = nextStart
        }
    }

    private fun merge(
        out: MutableMap<String, MutableMap<LocalDate, AvailabilityStatus>>,
        page: Map<String, Map<LocalDate, AvailabilityStatus>>,
    ) {
        for ((siteId, byDate) in page) {
            out.getOrPut(siteId) { linkedMapOf() }.putAll(byDate)
        }
    }

    private suspend fun primeSession(host: String) {
        sessionMutex.withLock {
            if (host in primedHosts) return@withLock
            get(host, "https://$host/welcome.do")
            primedHosts += host
        }
    }

    private suspend fun get(
        host: String,
        url: String,
    ): String {
        val req =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(VendorHttpDefaults.requestTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-CA,en;q=0.9")
                .header("Referer", "https://$host/")
                .GET()
                .build()
        val body =
            VendorHttpTransport.send(httpClient, req, "reserveamerica", url) { message, status, cause ->
                ReserveAmericaException(message, httpStatus = status, cause = cause)
            }
        return body
    }

    private fun matrixUrl(
        host: String,
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        startIdx: Int,
    ): String =
        "https://$host/campsiteCalendar.do?" +
            queryString(
                "page" to "calendar",
                "contractCode" to contractCode,
                "parkId" to parkId,
                "calarvdate" to matrixDate.format(startDate),
                "sitepage" to "true",
                "startIdx" to startIdx.toString(),
            )

    companion object {
        private const val PAGE_SIZE = 25
        private const val MATRIX_DAYS = 14
        private val matrixDate: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun defaultClient(): HttpClient =
            VendorHttpTransport.client {
                cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
            }
    }
}
