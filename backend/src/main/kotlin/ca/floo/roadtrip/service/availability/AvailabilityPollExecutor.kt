package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.ReservableAvailabilityFetchService
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.ProviderRefParser
import ca.floo.roadtrip.service.scheduler.HandlerResult
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Executes one polling job. Wired into [Scheduler] as the handler.
 *
 * Reservable-scope: fetches per-day availability through the booking
 * provider and appends snapshot rows. POI-scope: not yet implemented
 * (fan-out to child reservables is a separate concern).
 *
 * Per-run audit: every invocation writes one [AvailabilityJobRunRepo]
 * row. Successful runs (including no-op runs for unresolvable scopes
 * and POI-scope) are recorded as 'completed' with `snapshot_count`.
 * Upstream / unexpected exceptions are recorded as 'failed' with the
 * error message. Runs are never lost — even if `start` succeeds and
 * the work errors, the row gets a terminal status so the operator can
 * see the failure.
 *
 * Handler always returns a [HandlerResult] — even on upstream failure —
 * because losing the row would mean the watch silently stops polling.
 */
class AvailabilityPollExecutor(
    private val reservables: ReservableRepo,
    private val campsiteProviders: CampsiteProviderRepo,
    private val bookingProviders: BookingProviderRegistry,
    private val fetches: ReservableAvailabilityFetchService,
    private val runs: AvailabilityJobRunRepo,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(job: AvailabilityJobRepo.Job): HandlerResult {
        val startedAt = OffsetDateTime.now()
        val runId = runs.start(job.id, startedAt)
        var snapshotCount = 0
        try {
            val intent = AvailabilityJobIntent.fromJsonObject(job.intentPayload)
            snapshotCount =
                when (intent) {
                    is AvailabilityJobIntent.Reservable -> runReservable(job.id, runId, intent)
                    is AvailabilityJobIntent.Poi -> {
                        log.info("job {} POI scope not yet executed (poi_id={})", job.id, intent.poiId)
                        0
                    }
                }
            val completedAt = OffsetDateTime.now()
            val durationMs =
                java.time.Duration
                    .between(startedAt, completedAt)
                    .toMillis()
                    .toInt()
                    .coerceAtLeast(0)
            runs.complete(runId, snapshotCount, completedAt, durationMs)
        } catch (e: Exception) {
            log.warn("job {} run {} failed: {}", job.id, runId, e.message)
            val completedAt = OffsetDateTime.now()
            val durationMs =
                java.time.Duration
                    .between(startedAt, completedAt)
                    .toMillis()
                    .toInt()
                    .coerceAtLeast(0)
            runs.fail(runId, error = e.message ?: e::class.simpleName ?: "unknown", completedAt = completedAt, durationMs = durationMs)
        }
        return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(job.cadenceSec.toLong()))
    }

    /**
     * Runs a Reservable-scope intent. Returns the number of snapshot
     * rows the fetch produced (sized by the upstream's per-day window).
     * Returns 0 when the intent can't be executed (missing reservable,
     * no resolvable booking provider) — these are recorded as
     * successful no-op runs, not failures.
     */
    private suspend fun runReservable(
        jobId: Long,
        runId: Long,
        intent: AvailabilityJobIntent.Reservable,
    ): Int {
        val reservable =
            reservables.findById(intent.reservableId)
                ?: run {
                    log.warn("job {}: reservable {} no longer exists", jobId, intent.reservableId)
                    return 0
                }
        val poiIds = reservables.poiIdsForReservable(reservable.id)
        if (poiIds.isEmpty()) {
            log.warn("job {}: reservable {} has no POI parent", jobId, reservable.id)
            return 0
        }
        val refRowsById = campsiteProviders.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { refRowsById[it] }
                .firstOrNull { bookingProviders.forPoi(it) != null && ProviderRefParser.parse(it.providerRefJson) != null }
        if (parent == null) {
            log.warn("job {}: reservable {} has no resolvable booking provider", jobId, reservable.id)
            return 0
        }
        val provider = bookingProviders.forPoi(parent)!!
        val ref = ProviderRefParser.parse(parent.providerRefJson)!!

        val firstDate = intent.targetDates.firstOrNull() ?: return 0
        val start = LocalDate.parse(firstDate)
        val days =
            intent.targetDates
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .maxOrNull()
                ?.let {
                    java.time.temporal.ChronoUnit.DAYS
                        .between(start, it)
                        .toInt() + 1
                }
                ?: 1

        val response =
            fetches.fetch(
                ReservableAvailabilityFetchService.Request(
                    reservableId = reservable.id,
                    reservableRid = reservable.rid.encode(),
                    provider = provider,
                    ref = ref,
                    vendorId = reservable.rid.vendorId,
                    start = start,
                    days = days,
                    minNights = intent.minNights,
                    force = false,
                    runId = runId,
                ),
            )
        // Each day in the response window is one snapshot row in
        // reservable_availability_log (ReservableAvailabilityFetchService
        // calls appendAvailabilityPoll on the full response).
        return response.availability.size
    }
}
