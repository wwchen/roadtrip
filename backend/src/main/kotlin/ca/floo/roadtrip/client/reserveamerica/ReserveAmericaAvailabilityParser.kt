package ca.floo.roadtrip.client.reserveamerica

import ca.floo.roadtrip.client.AvailabilityPayloadReads
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.UnrecognizedStatusTally
import java.time.LocalDate

object ReserveAmericaAvailabilityParser {
    private val reads = AvailabilityPayloadReads("reserveamerica")
    private val siteLabelRegex = Regex("""<div class='siteListLabel'>""")
    private val siteIdRegex = Regex("""siteId=(\d+)""")
    private val statusCellRegex =
        Regex("""<div class='td status\s+([^']*)'[^>]*>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val resultTotalRegex = Regex("""id='resulttotal_dr_(?:top|bottom)'\s*>\s*(\d+)\s*</span>""")
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

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
        val rows = siteRows(html)
        val totalSites = totalSites(html)
        if (rows.isEmpty() && totalSites == null) {
            throw reads.unreadable("no siteListLabel rows and no resulttotal_dr marker: ${reads.excerpt(html)}")
        }
        val unrecognized = UnrecognizedStatusTally()
        for (row in rows) {
            val siteId = siteIdRegex.find(row)?.groupValues?.get(1) ?: continue
            val byDate = linkedMapOf<LocalDate, AvailabilityStatus>()
            statusCellRegex
                .findAll(row)
                .take(dates.size)
                .forEachIndexed { i, match ->
                    val code = cellCode(match.groupValues[1])
                    val label = stripTags(match.groupValues[2])
                    byDate[dates[i]] = unrecognized.orUnknown("$code/$label", classify(code.lowercase(), label.lowercase()))
                }
            if (byDate.isNotEmpty()) {
                statuses[siteId] = byDate
            }
        }
        if (rows.isNotEmpty() && dates.isNotEmpty() && statuses.isEmpty()) {
            throw reads.unreadable("${rows.size} site rows but no status cells: ${reads.excerpt(rows.first())}")
        }
        return ParsedReserveAmericaMatrix(
            statuses = statuses,
            totalSites = totalSites,
            unrecognizedStatuses = unrecognized.counts(),
        )
    }

    /**
     * One HTML slice per site row, split on the `siteListLabel` marker. Shared
     * by the availability parser (status cells) and [ReserveAmericaCatalogParser]
     * (roster), so both read the exact same `siteId` per row.
     */
    fun siteRows(html: String): List<String> {
        val starts = siteLabelRegex.findAll(html).map { it.range.first }.toList()
        return starts.mapIndexed { i, start ->
            html.substring(start, starts.getOrNull(i + 1) ?: html.length)
        }
    }

    private fun cellCode(classTail: String): String =
        classTail
            .trim()
            .split(whitespaceRegex)
            .firstOrNull()
            .orEmpty()

    private fun classify(
        code: String,
        label: String,
    ): AvailabilityStatus? =
        when {
            code == "a" || label == "a" -> AvailabilityStatus.AVAILABLE
            code == "r" || label == "r" -> AvailabilityStatus.RESERVED
            code == "w" || label == "w" -> AvailabilityStatus.FIRST_COME
            code == "u" || code == "x" || label == "u" || label == "x" -> AvailabilityStatus.CLOSED
            code == "p" || label == "p" -> AvailabilityStatus.UNKNOWN
            else -> null
        }

    private fun totalSites(html: String): Int? =
        resultTotalRegex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun stripTags(value: String): String = value.replace(tagRegex, "").trim()
}
