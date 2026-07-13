package ca.floo.roadtrip.clients.campflare

import ca.floo.roadtrip.models.availability.campflare.CampflareAvailability
import java.time.LocalDate

fun interface CampflareAvailabilityClient : AutoCloseable {
    suspend fun fetchAvailability(
        campgroundIds: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): CampflareAvailability

    override fun close() {}
}
