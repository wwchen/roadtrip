package ca.floo.campsite.recgov.booker.matching

import java.time.LocalDate
import java.time.YearMonth

/** Pure date-math: find consecutive available windows + filter campsites by alert constraints. */
object Matcher {
    private val AVAILABLE_STATUSES = setOf("Available", "Open")

    /** YYYY-MM-01 strings for every month touched by [start, end] inclusive. */
    fun monthsInRange(start: String, end: String): List<String> {
        val s = YearMonth.from(LocalDate.parse(start))
        val e = YearMonth.from(LocalDate.parse(end))
        val out = mutableListOf<String>()
        var cur = s
        while (!cur.isAfter(e)) {
            out += cur.atDay(1).toString()
            cur = cur.plusMonths(1)
        }
        return out
    }

    /**
     * Find every consecutive run of exactly `minNights` available days within [start, end).
     * Returns a list of date-list windows (each of length minNights).
     */
    fun findConsecutiveWindows(
        availabilities: Map<String, String>,
        startDate: String,
        endDate: String,
        minNights: Int,
    ): List<List<String>> {
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)

        val days = availabilities.entries
            .mapNotNull { (k, v) ->
                if (!AVAILABLE_STATUSES.contains(v)) return@mapNotNull null
                val day = LocalDate.parse(k.substring(0, 10))
                if (day < start || !day.isBefore(end)) return@mapNotNull null
                day
            }
            .sorted()

        if (days.size < minNights) return emptyList()

        val windows = mutableListOf<List<String>>()
        var runStart = 0
        for (i in 1..days.size) {
            val isConsecutive = i < days.size && days[i - 1].plusDays(1) == days[i]
            if (!isConsecutive) {
                val runLen = i - runStart
                if (runLen >= minNights) {
                    for (j in runStart..(runStart + runLen - minNights)) {
                        windows += days.subList(j, j + minNights).map { it.toString() }
                    }
                }
                runStart = i
            }
        }
        return windows
    }

    /** Returns true if the campsite passes the alert's filters. */
    fun passesCampsiteFilters(
        campsiteType: String?,
        site: String?,
        maxNumPeople: Int?,
        equipmentTypes: List<String>,
        alertCampsiteTypes: List<String>,
        alertEquipmentTypes: List<String>,
        alertSpecificSites: List<String>,
        alertMaxPeople: Int?,
    ): Boolean {
        if (alertCampsiteTypes.isNotEmpty() && campsiteType !in alertCampsiteTypes) return false
        if (alertSpecificSites.isNotEmpty() && site !in alertSpecificSites) return false
        if (alertMaxPeople != null && (maxNumPeople ?: 0) < alertMaxPeople) return false
        if (alertEquipmentTypes.isNotEmpty() && equipmentTypes.none { it in alertEquipmentTypes }) return false
        return true
    }
}
