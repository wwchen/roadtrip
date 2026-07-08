package ca.floo.roadtrip.service.reservation

import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val MONTHS_PER_YEAR: Int = 12
private const val MONTH_INDEX_OFFSET: Int = 1
private const val FIRST_DAY_OF_MONTH: Int = 1

/**
 * What an adapter supports. Surfaced to the FE through
 * availability capability surfaces so the drawer can hide UI
 * affordances the upstream can't honor.
 *
 * Conservative defaults: a new adapter answers "no" to every capability
 * until the corresponding interface is implemented. Lying upward — claiming
 * a capability the adapter can't deliver — is the worst failure mode.
 */
data class ReservationProviderCapabilities(
    /** Can serve per-day availability for a date window. */
    val supportsAvailability: Boolean,
    /** Can be polled in the background to drive watches. */
    val supportsAlerts: Boolean,
    /**
     * Widest window, in days, the poller asks this vendor for in a single
     * tick. This is a load knob, distinct from [bookingHorizon] (how far the
     * upstream exposes): the poller always polls `[today, today +
     * maxPollWindowDays)` — clamped to the horizon — independent of any watch's
     * dates. A watch gates *whether* a poller runs (reference count), never
     * *how wide* it fetches. Keep it inside a single upstream fetch shape
     * (e.g. rec.gov shapes calls by month) so one tick doesn't fan out into
     * ungoverned sub-calls. Zero means "don't poll" (unsupported stub).
     */
    val maxPollWindowDays: Int,
    /** User/operator-facing booking horizon in the vendor's native unit. */
    val bookingHorizon: CapabilityLimit,
    /** User/operator-facing single upstream fetch window cap. */
    val fetchWindowCap: CapabilityLimit,
) {
    companion object {
        /** Reasonable starting point for a stub — can be flipped on as features land. */
        val UNSUPPORTED: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = false,
                supportsAlerts = false,
                maxPollWindowDays = 0,
                bookingHorizon = CapabilityLimit(0, ChronoUnit.DAYS),
                fetchWindowCap = CapabilityLimit(0, ChronoUnit.DAYS),
            )
    }
}

data class CapabilityLimit(
    val value: Int,
    val unit: ChronoUnit,
) {
    init {
        require(unit == ChronoUnit.DAYS || unit == ChronoUnit.MONTHS) {
            "CapabilityLimit supports only DAYS and MONTHS, got $unit"
        }
    }

    fun endExclusiveFrom(startDate: LocalDate): LocalDate =
        when (unit) {
            ChronoUnit.DAYS -> startDate.plusDays(value.toLong())
            ChronoUnit.MONTHS -> startDate.plusMonths(value.toLong())
            else -> unsupportedUnit()
        }

    fun windowCovering(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Pair<LocalDate, LocalDate>? {
        if (value <= 0 || !endDate.isAfter(startDate)) return null
        val start = bucketStart(startDate)
        val end = bucketStart(endDate.minusDays(1)).plusLimit()
        return start to end
    }

    private fun bucketStart(date: LocalDate): LocalDate =
        when (unit) {
            ChronoUnit.DAYS -> dayBucketStart(date)
            ChronoUnit.MONTHS -> monthBucketStart(date)
            else -> unsupportedUnit()
        }

    private fun dayBucketStart(date: LocalDate): LocalDate {
        val bucketDays = value.toLong()
        return LocalDate.ofEpochDay(Math.floorDiv(date.toEpochDay(), bucketDays) * bucketDays)
    }

    private fun monthBucketStart(date: LocalDate): LocalDate {
        val monthIndex = date.year * MONTHS_PER_YEAR + date.monthValue - MONTH_INDEX_OFFSET
        val bucketIndex = Math.floorDiv(monthIndex, value) * value
        val year = Math.floorDiv(bucketIndex, MONTHS_PER_YEAR)
        val month = Math.floorMod(bucketIndex, MONTHS_PER_YEAR) + MONTH_INDEX_OFFSET
        return LocalDate.of(year, month, FIRST_DAY_OF_MONTH)
    }

    private fun LocalDate.plusLimit(): LocalDate =
        when (unit) {
            ChronoUnit.DAYS -> plusDays(value.toLong())
            ChronoUnit.MONTHS -> plusMonths(value.toLong())
            else -> unsupportedUnit()
        }

    private fun unsupportedUnit(): Nothing = error("CapabilityLimit supports only DAYS and MONTHS, got $unit")
}
