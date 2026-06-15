package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.ReservableAvailabilityLogRepo
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.ReservableAvailabilityRequest
import org.slf4j.LoggerFactory
import java.time.LocalDate

class ReservableAvailabilityFetchService(
    private val availabilityLogs: ReservableAvailabilityLogRepo? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Request(
        val reservableRid: String,
        val provider: BookingProvider,
        val ref: ProviderRef,
        val vendorId: String,
        val start: LocalDate,
        val days: Int,
        val minNights: Int,
        val force: Boolean,
    )

    suspend fun fetch(request: Request): AvailabilityResponseDto {
        val response =
            request.provider.reservableAvailability(
                ReservableAvailabilityRequest(
                    ref = request.ref,
                    vendorId = request.vendorId,
                    start = request.start,
                    days = request.days,
                    minNights = request.minNights,
                    force = request.force,
                ),
            )
        appendBaseAvailabilityLog(request, response)
        return response
    }

    private suspend fun appendBaseAvailabilityLog(
        request: Request,
        response: AvailabilityResponseDto,
    ) {
        val logs = availabilityLogs ?: return
        try {
            val logResponse =
                if (request.minNights == 1) {
                    response
                } else {
                    request.provider.reservableAvailability(
                        ReservableAvailabilityRequest(
                            ref = request.ref,
                            vendorId = request.vendorId,
                            start = request.start,
                            days = request.days + request.minNights - 1,
                            minNights = 1,
                            force = false,
                        ),
                    )
                }
            logs.appendAvailabilityPoll(
                ReservableAvailabilityLogRepo.AvailabilityPoll(
                    reservableRid = request.reservableRid,
                    response = logResponse,
                ),
            )
        } catch (e: Exception) {
            log.warn("reservable availability log append failed rid={}: {}", request.reservableRid, e.message)
        }
    }
}
