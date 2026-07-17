package ca.floo.roadtrip.client.reservecalifornia

import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
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
