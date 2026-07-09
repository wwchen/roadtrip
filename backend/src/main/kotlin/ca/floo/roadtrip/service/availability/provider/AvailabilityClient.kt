package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import java.time.LocalDate

/**
 * Provider-neutral availability operations shared by every reservation vendor
 * adapter. Implementations may call wildly different upstream APIs, but above
 * this contract the app only deals in typed inputs and normalized
 * [AvailabilityObservationBatch] results.
 */
interface AvailabilityClient {
    suspend fun availability(
        ref: ProviderRef,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch

    suspend fun catalogAvailability(
        ref: ProviderRef,
        reservables: List<CatalogReservableRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch

    suspend fun reservableAvailability(
        ref: ProviderRef,
        vendorId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch
}
