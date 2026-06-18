package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import org.slf4j.LoggerFactory
import java.time.LocalDate

class ReservableAvailabilityFetchService(
    private val snapshots: AvailabilitySnapshotRepo? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Request(
        val reservableId: Long,
        val reservableRid: String,
        val provider: ReservationProvider,
        val ref: ProviderRef,
        val vendorId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val force: Boolean,
        val runId: Long? = null,
    )

    suspend fun fetch(request: Request): AvailabilityResponseDto {
        val batch =
            request.provider.reservableAvailability(
                ReservableAvailabilityRequest(
                    ref = request.ref,
                    vendorId = request.vendorId,
                    startDate = request.startDate,
                    endDate = request.endDate,
                    force = request.force,
                ),
            )
        val response = availabilityResponseFromObservations(batch)
        appendBaseAvailabilitySnapshot(request, batch)
        return response
    }

    private suspend fun appendBaseAvailabilitySnapshot(
        request: Request,
        batch: AvailabilityObservationBatch,
    ) {
        val sink = snapshots ?: return
        val observations =
            batch.observations
                .filter { it.reservableId == request.reservableRid }
                .map { observation ->
                    AvailabilitySnapshotRepo.SnapshotObservation(
                        reservableId = request.reservableId,
                        reservableRid = request.reservableRid,
                        targetDate = observation.date,
                        observedAt = observation.observedAt,
                        status = observation.status,
                    )
                }
        try {
            sink.appendObservations(
                AvailabilitySnapshotRepo.SnapshotObservationBatch(
                    runId = request.runId,
                    observations = observations,
                ),
            )
        } catch (e: Exception) {
            log.warn(
                "availability snapshot append failed reservable_id={} rid={}: {}",
                request.reservableId,
                request.reservableRid,
                e.message,
            )
        }
    }
}
