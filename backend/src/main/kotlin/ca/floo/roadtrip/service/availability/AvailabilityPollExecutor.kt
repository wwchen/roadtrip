package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityJobRepo
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
 * provider and appends snapshot rows. POI-scope: deferred to PR 3
 * (fan-out logic).
 *
 * Handler always returns a [HandlerResult] — even on upstream failure —
 * because losing the row would mean the watch silently stops polling.
 */
class AvailabilityPollExecutor(
    private val reservables: ReservableRepo,
    private val campsiteProviders: CampsiteProviderRepo,
    private val bookingProviders: BookingProviderRegistry,
    private val fetches: ReservableAvailabilityFetchService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(job: AvailabilityJobRepo.Job): HandlerResult {
        try {
            val intent = AvailabilityJobIntent.fromJsonObject(job.intentPayload)
            when (intent) {
                is AvailabilityJobIntent.Reservable -> runReservable(job.id, intent)
                is AvailabilityJobIntent.Poi -> {
                    log.info("job {} POI scope not yet executed (poi_id={})", job.id, intent.poiId)
                }
            }
        } catch (e: Exception) {
            log.warn("job {} failed: {}", job.id, e.message)
        }
        return HandlerResult(nextRunAt = OffsetDateTime.now().plusSeconds(job.cadenceSec.toLong()))
    }

    private suspend fun runReservable(
        jobId: Long,
        intent: AvailabilityJobIntent.Reservable,
    ) {
        val reservable =
            reservables.findById(intent.reservableId)
                ?: run {
                    log.warn("job {}: reservable {} no longer exists", jobId, intent.reservableId)
                    return
                }
        val poiIds = reservables.poiIdsForReservable(reservable.id)
        if (poiIds.isEmpty()) {
            log.warn("job {}: reservable {} has no POI parent", jobId, reservable.id)
            return
        }
        val refRowsById = campsiteProviders.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { refRowsById[it] }
                .firstOrNull { bookingProviders.forPoi(it) != null && ProviderRefParser.parse(it.providerRefJson) != null }
        if (parent == null) {
            log.warn("job {}: reservable {} has no resolvable booking provider", jobId, reservable.id)
            return
        }
        val provider = bookingProviders.forPoi(parent)!!
        val ref = ProviderRefParser.parse(parent.providerRefJson)!!

        val firstDate = intent.targetDates.firstOrNull() ?: return
        val start = LocalDate.parse(firstDate)
        // Span the full window so a single fetch covers every target date.
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

        fetches.fetch(
            ReservableAvailabilityFetchService.Request(
                reservableRid = reservable.rid.encode(),
                provider = provider,
                ref = ref,
                vendorId = reservable.rid.vendorId,
                start = start,
                days = days,
                minNights = intent.minNights,
                force = false,
            ),
        )
    }
}
