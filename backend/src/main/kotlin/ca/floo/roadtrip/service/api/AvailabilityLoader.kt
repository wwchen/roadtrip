package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilitySeasonBlock
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.service.availability.hasFullCoverage
import ca.floo.roadtrip.service.availability.isFresh
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Loads availability for a window, deciding per request whether the stored data
 * suffices or a live upstream call is needed. It reads current state from the
 * [AvailabilityRepo] interval table; if the window has full, in-TTL coverage it
 * returns those rows, otherwise it fetches from the provider, records the
 * observations, and re-reads. There is one store (the interval table) — the
 * choice is "serve fresh-from-DB or go live", not "cache vs. source of truth".
 * The single-table transaction lives in the repo — this service only decides
 * *when* to fetch and record.
 */
class AvailabilityLoader(
    private val availability: AvailabilityRepo?,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class TargetReservable(
        val dbId: Long,
    )

    data class Metadata(
        val provider: String,
        val campgroundId: String? = null,
        val host: String? = null,
        val mapId: String? = null,
        val reservableId: String? = null,
    )

    data class Request(
        val metadata: Metadata,
        val targets: List<TargetReservable>,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val ttl: Duration,
        val runId: Long? = null,
    )

    suspend fun loadOrFetch(
        request: Request,
        fetch: suspend () -> AvailabilityObservationBatch,
    ): AvailabilityObservationBatch {
        val repo = availability
        // No store (or nothing to key on): pass through, but still slice the fetched
        // window down to the requested one. sliceToTarget normalizes the cacheBlock
        // (hit=false) — the raw batch's own cacheBlock is not preserved on this path.
        if (repo == null || request.targets.isEmpty()) return sliceToTarget(fetch(), request)

        val dates = datesInWindow(request.startDate, request.endDate)
        val dbIds = request.targets.map { it.dbId }
        val cached = repo.readCurrent(dbIds, dates)
        if (hasFullCoverage(request.targets.size, dates.size, cached.size) &&
            isFresh(cached.map { it.observedAt.toInstant() }, Instant.now(clock), request.ttl)
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

    /**
     * Narrow a fetched batch to the request's target window. The composer may fetch a
     * wider window than requested (so a later in-span request is a DB hit), but the
     * loader's contract is to return only the target window on every path — the
     * repo-less/no-target early return and the miss-fallback branch both go through here.
     */
    private fun sliceToTarget(
        fetched: AvailabilityObservationBatch,
        request: Request,
    ): AvailabilityObservationBatch {
        val targetDates = datesInWindow(request.startDate, request.endDate).toSet()
        return fetched.copy(
            startDate = request.startDate,
            endDate = request.endDate,
            observations = fetched.observations.filter { it.date in targetDates },
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = request.ttl.seconds),
        )
    }

    /**
     * Record every returned observation, plus an UNKNOWN cell for each (target, date)
     * the provider omitted, so the window reaches full coverage and the next read is a
     * cache hit rather than a refetch loop. `recordObservations` bumps unchanged cells
     * and inserts a status-run only on a change.
     */
    private fun recordFetched(
        repo: AvailabilityRepo,
        request: Request,
        batch: AvailabilityObservationBatch,
    ) {
        val targetByWireId = request.targets.associateBy { it.dbId.toString() }
        val dates = datesInWindow(batch.startDate, batch.endDate)
        val observedAtByDate =
            batch.observations.groupBy { it.date }.mapValues { (_, o) -> o.maxOf { it.observedAt } }
        val fallbackObservedAt = batch.observations.maxOfOrNull { it.observedAt } ?: Instant.now(clock)
        val covered = mutableSetOf<Pair<Long, LocalDate>>()
        val observations = mutableListOf<AvailabilityRepo.Observation>()
        for (o in batch.observations) {
            val target = targetByWireId[o.reservableId] ?: continue
            covered += target.dbId to o.date
            observations += AvailabilityRepo.Observation(target.dbId, o.date, o.status, o.observedAt)
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
                    ReservableDayObservation(
                        reservableId = row.reservableId.toString(),
                        date = row.targetDate,
                        observedAt = row.observedAt.toInstant(),
                        status = row.status,
                    )
                },
            cacheBlock = AvailabilityCacheBlock(hit = hit, ageSeconds = maxAgeSeconds(rows, now), ttlSeconds = request.ttl.seconds),
            seasonBlock = seasonBlock,
            campgroundId = request.metadata.campgroundId,
            host = request.metadata.host,
            mapId = request.metadata.mapId,
            reservableId = request.metadata.reservableId,
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
            reservableId = batch.reservableId ?: fallback.reservableId,
        )

    private fun maxAgeSeconds(
        rows: List<AvailabilityRepo.CurrentCell>,
        now: Instant,
    ): Long = rows.maxOfOrNull { Duration.between(it.observedAt.toInstant(), now).seconds.coerceAtLeast(0) } ?: 0

    private fun datesInWindow(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<LocalDate> = (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt()).map { startDate.plusDays(it.toLong()) }
}
