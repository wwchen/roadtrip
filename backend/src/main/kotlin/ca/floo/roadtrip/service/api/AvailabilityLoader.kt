package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampgroundObservations
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.availability.AvailabilityCell
import ca.floo.roadtrip.service.availability.requiredCells
import ca.floo.roadtrip.service.availability.unansweredCells
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Loads availability for a window, deciding per request whether the stored
 * interval rows are fresh enough or a live upstream call is needed.
 */
class AvailabilityLoader(
    private val availabilityRepo: AvailabilityRepo?,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class CampsiteTarget(
        val dbId: Long,
    )

    data class Metadata(
        val provider: String,
        val scope: BookingProviderRef? = null,
        val campsiteId: Long? = null,
    )

    data class Request(
        val metadata: Metadata,
        val targets: List<CampsiteTarget>,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val freshAtOrAfter: Instant,
        val runId: Long? = null,
        val fetchStartDate: LocalDate = startDate,
        val fetchEndDate: LocalDate = endDate,
    )

    suspend fun loadOrFetch(
        request: Request,
        fetch: suspend () -> CampgroundObservations,
    ): AvailabilityObservationBatch {
        val repo = availabilityRepo
        if (repo == null || request.targets.isEmpty()) return sliceToTarget(fetch(), request)

        val dates = datesInWindow(request.startDate, request.endDate)
        val dbIds = request.targets.map { it.dbId }.distinct()
        val required = requiredCells(dbIds, dates)
        val cached = repo.readCurrent(dbIds, dates)
        // Per cell, not per batch: the store answers this request when every
        // cell it asked about has a current observation that is fresh enough.
        // The upstream call cannot be narrowed below a campground window behind
        // `AvailabilityProvider`, so one unanswered cell still costs one
        // provider call — but a window whose cells are all answered costs none.
        if (unansweredCells(required, observedAtByCell(cached), request.freshAtOrAfter).isEmpty()) {
            return batchFromLatest(request, cached, hit = true)
        }

        val fetched = fetch()
        recordFetched(repo, request, fetched)

        val latest = repo.readCurrent(dbIds, dates)
        return if (cellsOf(latest).containsAll(required)) {
            batchFromLatest(
                request = request.copy(metadata = metadataFromFetched(fetched, request.metadata)),
                rows = latest,
                hit = false,
            )
        } else {
            sliceToTarget(fetched, request)
        }
    }

    private fun sliceToTarget(
        fetched: CampgroundObservations,
        request: Request,
    ): AvailabilityObservationBatch {
        val targetDates = datesInWindow(request.startDate, request.endDate).toSet()
        val targetIds = request.targets.mapTo(mutableSetOf()) { it.dbId }
        return AvailabilityObservationBatch(
            provider = request.metadata.provider,
            startDate = request.startDate,
            endDate = request.endDate,
            // The batch covers the whole campground; the response is only about
            // the campsites this request named. With no targets there is nothing
            // to narrow to, so the batch stands as fetched.
            observations =
                fetched.observations.filter {
                    it.date in targetDates && (targetIds.isEmpty() || it.campsiteId in targetIds)
                },
            cacheBlock =
                AvailabilityCacheBlock(
                    hit = false,
                    ageSeconds = 0,
                    ttlSeconds = effectiveTtlSeconds(request),
                ),
            scope = fetched.campgroundRef ?: request.metadata.scope,
            campsiteId = request.metadata.campsiteId,
        )
    }

    private fun recordFetched(
        repo: AvailabilityRepo,
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
        repo.recordObservations(request.runId, observations)
    }

    private fun batchFromLatest(
        request: Request,
        rows: List<AvailabilityRepo.CurrentCell>,
        hit: Boolean,
    ): AvailabilityObservationBatch {
        val now = Instant.now(clock)
        return AvailabilityObservationBatch(
            provider = request.metadata.provider,
            startDate = request.startDate,
            endDate = request.endDate,
            observations =
                rows.map { row ->
                    CampsiteDayObservation(
                        campsiteId = row.campsiteId,
                        date = row.targetDate,
                        observedAt = row.observedAt.toInstant(),
                        status = row.status,
                    )
                },
            cacheBlock =
                AvailabilityCacheBlock(
                    hit = hit,
                    ageSeconds = maxAgeSeconds(rows, now),
                    ttlSeconds = effectiveTtlSeconds(request),
                ),
            scope = request.metadata.scope,
            campsiteId = request.metadata.campsiteId,
        )
    }

    private fun metadataFromFetched(
        fetched: CampgroundObservations,
        fallback: Metadata,
    ): Metadata = fallback.copy(scope = fetched.campgroundRef ?: fallback.scope)

    private fun observedAtByCell(rows: List<AvailabilityRepo.CurrentCell>): Map<AvailabilityCell, Instant> =
        rows.associate { AvailabilityCell(it.campsiteId, it.targetDate) to it.observedAt.toInstant() }

    private fun cellsOf(rows: List<AvailabilityRepo.CurrentCell>): Set<AvailabilityCell> =
        rows.mapTo(mutableSetOf()) { AvailabilityCell(it.campsiteId, it.targetDate) }

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
