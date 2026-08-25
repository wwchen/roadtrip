package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.availability.hasFullCoverage
import ca.floo.roadtrip.service.availability.isFreshAsOf
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
        val campgroundId: String? = null,
        val host: String? = null,
        val mapId: String? = null,
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
        val dbIds = request.targets.map { it.dbId }
        val cached = repo.readCurrent(dbIds, dates)
        if (hasFullCoverage(request.targets.size, dates.size, cached.size) &&
            isFreshAsOf(cached.map { it.observedAt.toInstant() }, request.freshAtOrAfter)
        ) {
            return batchFromLatest(request, cached, hit = true)
        }

        val fetched = fetch()
        recordFetched(repo, request, fetched)

        val latest = repo.readCurrent(dbIds, dates)
        return if (hasFullCoverage(request.targets.size, dates.size, latest.size)) {
            batchFromLatest(
                request = request.copy(metadata = metadataFromBatch(fetched, request.metadata)),
                rows = latest,
                hit = false,
                seasonBlock = fetched.seasonBlock,
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
        return fetched.copy(
            startDate = request.startDate,
            endDate = request.endDate,
            observations = fetched.observations.filter { it.date in targetDates },
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
        val targetIds = request.targets.mapTo(mutableSetOf()) { it.dbId }
        val dates = datesInWindow(batch.startDate, batch.endDate)
        val observedAtByDate =
            batch.observations.groupBy { it.date }.mapValues { (_, observations) -> observations.maxOf { it.observedAt } }
        val fallbackObservedAt = batch.observations.maxOfOrNull { it.observedAt } ?: Instant.now(clock)
        val covered = mutableSetOf<Pair<Long, LocalDate>>()
        val observations = mutableListOf<AvailabilityRepo.Observation>()
        for (observation in batch.observations) {
            val campsiteId = observation.campsiteId?.takeIf { it in targetIds } ?: continue
            covered += campsiteId to observation.date
            observations += AvailabilityRepo.Observation(campsiteId, observation.date, observation.status, observation.observedAt)
        }
        for (target in request.targets) {
            for (date in dates) {
                if (target.dbId to date in covered) continue
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
        seasonBlock: AvailabilitySeasonBlock? = null,
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
            seasonBlock = seasonBlock,
            campgroundId = request.metadata.campgroundId,
            host = request.metadata.host,
            mapId = request.metadata.mapId,
            campsiteId = request.metadata.campsiteId,
        )
    }

    private fun metadataFromBatch(
        batch: AvailabilityObservationBatch,
        fallback: Metadata,
    ): Metadata =
        fallback.copy(
            provider = batch.provider,
            campgroundId = batch.campgroundId ?: fallback.campgroundId,
            host = batch.host ?: fallback.host,
            mapId = batch.mapId ?: fallback.mapId,
            campsiteId = batch.campsiteId ?: fallback.campsiteId,
        )

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
