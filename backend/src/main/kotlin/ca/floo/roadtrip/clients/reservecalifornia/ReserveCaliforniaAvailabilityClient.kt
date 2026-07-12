package ca.floo.roadtrip.clients.reservecalifornia

import java.time.LocalDate

interface ReserveCaliforniaAvailabilityClient : AutoCloseable {
    suspend fun fetchGrid(
        facilityId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
        minDate: LocalDate,
        maxDate: LocalDate,
    ): ReserveCaliforniaGridAvailability

    override fun close() {}
}
