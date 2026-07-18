package ca.floo.roadtrip.client.reserveamerica

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import java.time.LocalDate

object ReserveAmericaAvailabilityParser {
    private val siteLabelRegex = Regex("""<div class='siteListLabel'>""")
    private val siteIdRegex = Regex("""siteId=(\d+)""")
    private val statusCellRegex =
        Regex("""<div class='td status\s+([^']*)'[^>]*>(.*?)</div>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val resultTotalRegex = Regex("""id='resulttotal_dr_(?:top|bottom)'\s*>\s*(\d+)\s*</span>""")
    private val tagRegex = Regex("""<[^>]+>""")

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
        for (row in siteRows(html)) {
            val siteId = siteIdRegex.find(row)?.groupValues?.get(1) ?: continue
            val byDate = linkedMapOf<LocalDate, AvailabilityStatus>()
            statusCellRegex
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
        resultTotalRegex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun stripTags(value: String): String = value.replace(tagRegex, "").trim()
}
