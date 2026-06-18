package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class SnapshotBackedAvailabilityService(
    private val snapshots: AvailabilitySnapshotRepo?,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class TargetReservable(
        val dbId: Long,
        val rid: String,
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
        val force: Boolean,
        val runId: Long? = null,
    )

    suspend fun loadOrFetch(
        request: Request,
        fetch: suspend () -> AvailabilityObservationBatch,
    ): AvailabilityObservationBatch {
        val sink = snapshots
        if (sink == null || request.targets.isEmpty()) {
            return fetch()
        }

        val dates = datesInWindow(request.startDate, request.endDate)
        if (!request.force) {
            val latest = sink.loadLatestObservations(request.targets.map { it.dbId }, dates)
            if (hasFullFreshCoverage(request, dates, latest)) {
                return batchFromLatest(request, latest, hit = true)
            }
        }

        val fetched = fetch()
        appendKnownObservations(sink, request, fetched)

        val latest = sink.loadLatestObservations(request.targets.map { it.dbId }, dates)
        return if (hasFullCoverage(request, dates, latest)) {
            batchFromLatest(
                request = request.copy(metadata = metadataFromBatch(fetched, request.metadata)),
                rows = latest,
                hit = false,
                seasonBlock = fetched.seasonBlock,
            )
        } else {
            fetched.copy(cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = request.ttl.seconds))
        }
    }

    private fun appendKnownObservations(
        sink: AvailabilitySnapshotRepo,
        request: Request,
        batch: AvailabilityObservationBatch,
    ) {
        val targetByRid = request.targets.associateBy { it.rid }
        val dates = datesInWindow(request.startDate, request.endDate)
        val observedAtByDate =
            batch.observations
                .groupBy { it.date }
                .mapValues { (_, observations) ->
                    observations.maxOf { it.observedAt }
                }
        val fallbackObservedAt = batch.observations.maxOfOrNull { it.observedAt } ?: Instant.now(clock)
        val covered = mutableSetOf<Pair<Long, LocalDate>>()
        val rows = mutableListOf<AvailabilitySnapshotRepo.SnapshotObservation>()

        rows +=
            batch.observations.mapNotNull { observation ->
                val target = targetByRid[observation.reservableId] ?: return@mapNotNull null
                covered += target.dbId to observation.date
                AvailabilitySnapshotRepo.SnapshotObservation(
                    reservableId = target.dbId,
                    reservableRid = target.rid,
                    targetDate = observation.date,
                    observedAt = observation.observedAt,
                    status = observation.status,
                )
            }
        for (target in request.targets) {
            for (date in dates) {
                if (target.dbId to date in covered) continue
                rows +=
                    AvailabilitySnapshotRepo.SnapshotObservation(
                        reservableId = target.dbId,
                        reservableRid = target.rid,
                        targetDate = date,
                        observedAt = observedAtByDate[date] ?: fallbackObservedAt,
                        status = AvailabilityStatus.UNKNOWN,
                    )
            }
        }
        sink.appendObservations(
            AvailabilitySnapshotRepo.SnapshotObservationBatch(
                runId = request.runId,
                observations = rows,
            ),
        )
    }

    private fun hasFullFreshCoverage(
        request: Request,
        dates: List<LocalDate>,
        rows: List<AvailabilitySnapshotRepo.LatestObservation>,
    ): Boolean {
        if (!hasFullCoverage(request, dates, rows)) return false
        val freshAfter = Instant.now(clock).minus(request.ttl)
        return rows.all { !it.observedAt.toInstant().isBefore(freshAfter) }
    }

    private fun hasFullCoverage(
        request: Request,
        dates: List<LocalDate>,
        rows: List<AvailabilitySnapshotRepo.LatestObservation>,
    ): Boolean = rows.size == request.targets.size * dates.size

    private fun batchFromLatest(
        request: Request,
        rows: List<AvailabilitySnapshotRepo.LatestObservation>,
        hit: Boolean,
        seasonBlock: AvailabilitySeasonBlock? = null,
    ): AvailabilityObservationBatch {
        val ridByDbId = request.targets.associate { it.dbId to it.rid }
        val now = Instant.now(clock)
        return AvailabilityObservationBatch(
            provider = request.metadata.provider,
            startDate = request.startDate,
            endDate = request.endDate,
            observations =
                rows.map { row ->
                    ReservableDayObservation(
                        reservableId = ridByDbId[row.reservableId] ?: row.reservableId.toString(),
                        date = row.targetDate,
                        observedAt = row.observedAt.toInstant(),
                        status = row.status,
                    )
                },
            cacheBlock =
                AvailabilityCacheBlock(
                    hit = hit,
                    ageSeconds = maxAgeSeconds(rows, now),
                    ttlSeconds = request.ttl.seconds,
                ),
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
        rows: List<AvailabilitySnapshotRepo.LatestObservation>,
        now: Instant,
    ): Long =
        rows.maxOfOrNull { row ->
            Duration.between(row.observedAt.toInstant(), now).seconds.coerceAtLeast(0)
        } ?: 0

    private fun datesInWindow(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<LocalDate> =
        (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
            .map { startDate.plusDays(it.toLong()) }
}
