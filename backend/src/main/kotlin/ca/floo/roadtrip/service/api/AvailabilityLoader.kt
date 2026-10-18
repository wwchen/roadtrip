package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityStatus
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
    )

    suspend fun loadOrFetch(
        request: Request,
        fetch: suspend () -> AvailabilityObservationBatch,
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
                request = request.copy(metadata = metadataFromBatch(fetched, request.metadata)),
                rows = latest,
                hit = false,
            )
        } else {
            sliceToTarget(fetched, request)
        }
    }

    private fun sliceToTarget(
        fetched: AvailabilityObservationBatch,
        request: Request,
    ): AvailabilityObservationBatch {
        val targetDates = datesInWindow(request.startDate, request.endDate).toSet()
        val targetIds = request.targets.mapTo(mutableSetOf()) { it.dbId }
        return fetched.copy(
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
        )
    }

    private fun recordFetched(
        repo: AvailabilityRepo,
        request: Request,
        batch: AvailabilityObservationBatch,
    ) {
        val dates = datesInWindow(batch.startDate, batch.endDate)
        val observedAtByDate =
            batch.observations.groupBy { it.date }.mapValues { (_, observations) -> observations.maxOf { it.observedAt } }
        val fallbackObservedAt = batch.observations.maxOfOrNull { it.observedAt } ?: Instant.now(clock)
        val covered = mutableSetOf<AvailabilityCell>()
        val observations = mutableListOf<AvailabilityRepo.Observation>()
        // Every cell the vendor answered, whether or not this request's filter
        // asked about it. The upstream call covered the campground and we paid
        // for it; narrowing the write to the filtered targets is what left the
        // next unfiltered check with a hole to refetch.
        for (observation in batch.observations) {
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

    private fun metadataFromBatch(
        batch: AvailabilityObservationBatch,
        fallback: Metadata,
    ): Metadata =
        fallback.copy(
            provider = batch.provider,
            scope = batch.scope ?: fallback.scope,
            campsiteId = batch.campsiteId ?: fallback.campsiteId,
        )

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
