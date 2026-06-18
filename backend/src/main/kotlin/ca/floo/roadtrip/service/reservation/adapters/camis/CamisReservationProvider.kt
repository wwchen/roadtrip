package ca.floo.roadtrip.service.reservation.adapters.camis

import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.AvailableDatesRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId

/**
 * Camis (Alberta Parks) adapter — capability stub. The variant exists in
 * [ca.floo.roadtrip.models.ProviderRef] so the type system is exhaustive,
 * but no upstream client has been built. Capabilities all `false`; calls
 * throw [ReservationProviderError.Unsupported].
 *
 * Routes treat this the same way as a missing adapter: respond
 * `state: "empty"` so the FE drawer shows a benign "no availability data"
 * rather than a 503. The capability probe
 * lets the FE skip rendering the week grid entirely for these pins.
 *
 * Replace this class with a real adapter when Camis lands. Nothing outside
 * this directory should change.
 */
class CamisReservationProvider : ReservationProvider {
    override val id: ReservationProviderId = ReservationProviderId.CAMIS

    override val capabilities: ReservationProviderCapabilities = ReservationProviderCapabilities.UNSUPPORTED

    override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto =
        throw ReservationProviderError.Unsupported(operation = "availability", providerId = id)

    override suspend fun availableDates(req: AvailableDatesRequest): List<String> =
        throw ReservationProviderError.Unsupported(operation = "availableDates", providerId = id)
}
