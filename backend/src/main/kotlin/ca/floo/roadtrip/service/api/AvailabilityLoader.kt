package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampgroundObservations
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.availability.AvailabilityCell
import ca.floo.roadtrip.service.availability.requiredCells
import ca.floo.roadtrip.service.availability.unansweredCells
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class FetchedObservations(
    val observations: CampgroundObservations,
    val servedBy: BookingProvider?,
)

data class LoadedCells(
    val cells: List<CampsiteDayObservation>,
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
    val fetched: FetchedObservations?,
)

/**
 * Loads availability for a window, deciding per request whether the stored
 * interval rows are fresh enough or a live upstream call is needed.
 */
class AvailabilityLoader(
    private val availabilityRepo: AvailabilityRepo,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class CampsiteTarget(
        val dbId: Long,
    )

    data class Request(
        val targets: List<CampsiteTarget>,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val fetchStartDate: LocalDate,
        val fetchEndDate: LocalDate,
        val freshAtOrAfter: Instant,
        val runId: Long? = null,
    )

    suspend fun load(
        request: Request,
        fetch: suspend () -> FetchedObservations,
    ): LoadedCells {
        val dates = datesInWindow(request.startDate, request.endDate)
        val dbIds = request.targets.map { it.dbId }.distinct()
        val required = requiredCells(dbIds, dates)
        val cached = availabilityRepo.readCurrent(dbIds, dates)
        // Per cell, not per window: the store answers this request when every
        // cell it asked about has a current observation that is fresh enough.
        // The upstream call cannot be narrowed below a campground window behind
        // `AvailabilityProvider`, so one unanswered cell still costs one
        // provider call — but a window whose cells are all answered costs none.
        if (unansweredCells(required, observedAtByCell(cached), request.freshAtOrAfter).isEmpty()) {
            return storedCells(request, cached, hit = true)
        }

        val fetched = fetch()
        recordFetched(request, fetched.observations)

        return storedCells(request, availabilityRepo.readCurrent(dbIds, dates), hit = false, fetched = fetched)
    }

    private fun recordFetched(
        request: Request,
        fetched: CampgroundObservations,
    ) {
        val dates = datesInWindow(request.fetchStartDate, request.fetchEndDate)
        val observedAtByDate =
            fetched.observations.groupBy { it.date }.mapValues { (_, observations) -> observations.maxOf { it.observedAt } }
        val fallbackObservedAt = fetched.observations.maxOfOrNull { it.observedAt } ?: Instant.now(clock)
        val covered = mutableSetOf<AvailabilityCell>()
        val observations = mutableListOf<AvailabilityRepo.Observation>()
        // Every cell the vendor answered, whether or not this request's filter
        // asked about it. The upstream call covered the campground and we paid
        // for it; narrowing the write to the filtered targets is what left the
        // next unfiltered check with a hole to refetch.
        for (observation in fetched.observations) {
            val campsiteId = observation.campsiteId ?: continue
            covered += AvailabilityCell(campsiteId, observation.date)
            observations += AvailabilityRepo.Observation(campsiteId, observation.date, observation.status, observation.observedAt)
        }
        // The UNKNOWN back-fill stays with this request's own targets. Those are
        // the cells it promised an answer for, so "the vendor was asked and said
        // nothing" is true of them. Writing UNKNOWN for a campsite the request
        // never targeted would claim coverage on the strength of a question
        // nobody put.
        for (target in request.targets) {
            for (date in dates) {
                if (AvailabilityCell(target.dbId, date) in covered) continue
                observations +=
                    AvailabilityRepo.Observation(
                        target.dbId,
                        date,
                        AvailabilityStatus.UNKNOWN,
                        observedAtByDate[date] ?: fallbackObservedAt,
                    )
            }
        }
        availabilityRepo.recordObservations(request.runId, observations)
    }

    private fun storedCells(
        request: Request,
        rows: List<AvailabilityRepo.CurrentCell>,
        hit: Boolean,
        fetched: FetchedObservations? = null,
    ): LoadedCells =
        LoadedCells(
            cells =
                rows.map { row ->
                    CampsiteDayObservation(
                        campsiteId = row.campsiteId,
                        date = row.targetDate,
                        observedAt = row.observedAt.toInstant(),
                        status = row.status,
                    )
                },
            hit = hit,
            ageSeconds = maxAgeSeconds(rows, Instant.now(clock)),
            ttlSeconds = effectiveTtlSeconds(request),
            fetched = fetched,
        )

    private fun observedAtByCell(rows: List<AvailabilityRepo.CurrentCell>): Map<AvailabilityCell, Instant> =
        rows.associate { AvailabilityCell(it.campsiteId, it.targetDate) to it.observedAt.toInstant() }

    private fun maxAgeSeconds(
        rows: List<AvailabilityRepo.CurrentCell>,
        now: Instant,
    ): Long = rows.maxOfOrNull { Duration.between(it.observedAt.toInstant(), now).seconds.coerceAtLeast(0) } ?: 0

    private fun effectiveTtlSeconds(request: Request): Long =
        Duration.between(request.freshAtOrAfter, Instant.now(clock)).seconds.coerceAtLeast(0)

    private fun datesInWindow(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<LocalDate> =
        (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
            .map { startDate.plusDays(it.toLong()) }
}
