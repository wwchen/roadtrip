package ca.floo.roadtrip.clients.reserveamerica

import ca.floo.roadtrip.models.availability.AvailabilityStatus
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun interface ReserveAmericaAvailabilityClient {
    suspend fun fetch(
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ReserveAmericaAvailability
}

class HttpReserveAmericaAvailabilityClient(
    private val host: String,
    private val client: HttpClient = defaultClient(),
) : ReserveAmericaAvailabilityClient {
    private val sessionMutex = Mutex()
    private var sessionPrimed = false

    override suspend fun fetch(
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ReserveAmericaAvailability {
        primeSession()
        val observedAt = Instant.now()
        val statuses = linkedMapOf<String, MutableMap<LocalDate, AvailabilityStatus>>()
        var cursor = startDate
        while (cursor.isBefore(endDate)) {
            val pageEnd = minOf(cursor.plusDays(MATRIX_DAYS.toLong()), endDate)
            fetchWindow(contractCode, parkId, cursor, pageEnd, statuses)
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
        contractCode: String,
        parkId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        out: MutableMap<String, MutableMap<LocalDate, AvailabilityStatus>>,
    ) {
        var startIdx = 0
        var totalSites: Int? = null
        while (totalSites == null || startIdx < totalSites) {
            val html = get(matrixUrl(contractCode, parkId, startDate, startIdx))
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

    private suspend fun primeSession() {
        if (sessionPrimed) return
        sessionMutex.withLock {
            if (sessionPrimed) return@withLock
            get("https://$host/welcome.do")
            sessionPrimed = true
        }
    }

    private suspend fun get(url: String): String {
        val req =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-CA,en;q=0.9")
                .header("Referer", "https://$host/")
                .GET()
                .build()
        val resp =
            try {
                client.sendAsync(req, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: Exception) {
                throw ReserveAmericaException("reserveamerica request failed: ${e.message}", httpStatus = null)
            }
        if (resp.statusCode() !in 200..299) {
            throw ReserveAmericaException("reserveamerica HTTP ${resp.statusCode()} for $url", resp.statusCode())
        }
        return resp.body()
    }

    private fun matrixUrl(
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
                "calarvdate" to MATRIX_DATE.format(startDate),
                "sitepage" to "true",
                "startIdx" to startIdx.toString(),
            )

    companion object {
        private const val PAGE_SIZE = 25
        private const val MATRIX_DAYS = 14
        private val MATRIX_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US)
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun defaultClient(): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build()
    }
}

object ReserveAmericaAvailabilityParser {
    fun parse(
        html: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): ParsedReserveAmericaMatrix {
        val dayCount =
            minOf(14L, endDate.toEpochDay() - startDate.toEpochDay())
                .coerceAtLeast(0L)
                .toInt()
        val dates = (0 until dayCount).map { startDate.plusDays(it.toLong()) }
        val statuses = linkedMapOf<String, Map<LocalDate, AvailabilityStatus>>()
        val rowStarts = SITE_LABEL.findAll(html).map { it.range.first }.toList()
        for ((index, rowStart) in rowStarts.withIndex()) {
            val rowEnd = rowStarts.getOrNull(index + 1) ?: html.length
            val row = html.substring(rowStart, rowEnd)
            val siteId = SITE_ID.find(row)?.groupValues?.get(1) ?: continue
            val byDate = linkedMapOf<LocalDate, AvailabilityStatus>()
            STATUS_CELL
                .findAll(row)
                .take(dates.size)
                .forEachIndexed { i, match ->
                    byDate[dates[i]] = classify(match.groupValues[1], stripTags(match.groupValues[2]))
                }
            if (byDate.isNotEmpty()) {
                statuses[siteId] = byDate
            }
        }
        return ParsedReserveAmericaMatrix(statuses = statuses, totalSites = totalSites(html))
    }

    private fun classify(
        classTail: String,
        text: String,
    ): AvailabilityStatus {
        val code =
            classTail
                .trim()
                .split(Regex("""\s+"""))
                .firstOrNull()
                .orEmpty()
                .lowercase()
        val label = text.trim().lowercase()
        return when {
            code == "a" || label == "a" -> AvailabilityStatus.AVAILABLE
            code == "r" || label == "r" -> AvailabilityStatus.RESERVED
            code == "w" || label == "w" -> AvailabilityStatus.FIRST_COME
            code == "u" || code == "x" || label == "u" || label == "x" -> AvailabilityStatus.CLOSED
            else -> AvailabilityStatus.UNKNOWN
        }
    }

    private fun totalSites(html: String): Int? =
        RESULT_TOTAL
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun stripTags(value: String): String = value.replace(TAG, "").trim()

    private val SITE_LABEL = Regex("""<div class='siteListLabel'>""")
    private val SITE_ID = Regex("""siteId=(\d+)""")
    private val STATUS_CELL =
        Regex("""<div class='td status\s+([^']*)'[^>]*>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RESULT_TOTAL = Regex("""id='resulttotal_dr_(?:top|bottom)'\s*>\s*(\d+)\s*</span>""")
    private val TAG = Regex("""<[^>]+>""")
}

data class ParsedReserveAmericaMatrix(
    val statuses: Map<String, Map<LocalDate, AvailabilityStatus>>,
    val totalSites: Int?,
)

data class ReserveAmericaAvailability(
    val contractCode: String,
    val parkId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val observedAt: Instant,
    val statuses: Map<String, Map<LocalDate, AvailabilityStatus>>,
)

class ReserveAmericaException(
    message: String,
    val httpStatus: Int? = null,
) : RuntimeException(message)

private fun queryString(vararg params: Pair<String, String>): String =
    params.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
