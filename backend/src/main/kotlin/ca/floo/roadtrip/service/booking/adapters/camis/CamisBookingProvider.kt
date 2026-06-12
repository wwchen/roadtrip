package ca.floo.roadtrip.service.booking.adapters.camis

import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.booking.AvailabilityRequest
import ca.floo.roadtrip.service.booking.AvailableDatesRequest
import ca.floo.roadtrip.service.booking.BookingCapabilities
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderError
import ca.floo.roadtrip.service.booking.BookingProviderId

/**
 * Camis (Alberta Parks) adapter — capability stub. The variant exists in
 * [ca.floo.roadtrip.models.ProviderRef] so the type system is exhaustive,
 * but no upstream client has been built. Capabilities all `false`; calls
 * throw [BookingProviderError.Unsupported].
 *
 * Routes treat this the same way as a missing adapter: respond
 * `state: "empty"` so the FE drawer shows a benign "no availability data"
 * rather than a 503. The capability probe (`/api/campsite/capabilities`)
 * lets the FE skip rendering the week grid entirely for these pins.
 *
 * Replace this class with a real adapter when Camis lands. Nothing outside
 * this directory should change.
 */
class CamisBookingProvider : BookingProvider {
    override val id: BookingProviderId = BookingProviderId.CAMIS

    override val capabilities: BookingCapabilities = BookingCapabilities.UNSUPPORTED

    override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto =
        throw BookingProviderError.Unsupported(operation = "availability", providerId = id)

    override suspend fun availableDates(req: AvailableDatesRequest): List<String> =
        throw BookingProviderError.Unsupported(operation = "availableDates", providerId = id)
}
