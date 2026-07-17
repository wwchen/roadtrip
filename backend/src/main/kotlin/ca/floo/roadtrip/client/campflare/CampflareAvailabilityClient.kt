package ca.floo.roadtrip.client.campflare

import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import java.time.LocalDate

fun interface CampflareAvailabilityClient : AutoCloseable {
    suspend fun fetchAvailability(
        campgroundIds: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): CampflareAvailability

    override fun close() {}
}
