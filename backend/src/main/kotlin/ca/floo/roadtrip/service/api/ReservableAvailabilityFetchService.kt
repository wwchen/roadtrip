package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.ReservableAvailabilityRequest
import org.slf4j.LoggerFactory
import java.time.LocalDate

class ReservableAvailabilityFetchService(
    private val snapshots: AvailabilitySnapshotRepo? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Request(
        val reservableId: Long,
        val reservableRid: String,
        val provider: BookingProvider,
        val ref: ProviderRef,
        val vendorId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val force: Boolean,
        val runId: Long? = null,
    )

    suspend fun fetch(request: Request): AvailabilityResponseDto {
        val response =
            request.provider.reservableAvailability(
                ReservableAvailabilityRequest(
                    ref = request.ref,
                    vendorId = request.vendorId,
                    startDate = request.startDate,
                    endDate = request.endDate,
                    force = request.force,
                ),
            )
        appendBaseAvailabilitySnapshot(request, response)
        return response
    }

    private suspend fun appendBaseAvailabilitySnapshot(
        request: Request,
        response: AvailabilityResponseDto,
    ) {
        val sink = snapshots ?: return
        try {
            sink.appendBatch(
                AvailabilitySnapshotRepo.SnapshotBatch(
                    reservableId = request.reservableId,
                    runId = request.runId,
                    response = response,
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
