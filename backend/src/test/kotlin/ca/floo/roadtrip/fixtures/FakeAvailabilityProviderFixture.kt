package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.LocalDate

/** rec.gov's real horizon; the default for fakes that don't care. */
const val FAKE_PROVIDER_HORIZON_DAYS = 180

/** A year out, for fakes standing in for providers that book further ahead. */
const val FAKE_PROVIDER_YEAR_HORIZON_DAYS = 365

const val FAKE_PROVIDER_MAX_POLL_WINDOW_DAYS = 60

private const val UNSTUBBED_AVAILABILITY = "FakeAvailabilityProvider.availability was not stubbed for this test"

/** One recorded [FakeAvailabilityProvider.availability] call. */
data class FakeAvailabilityCall(
    val campground: Campground,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

/**
 * Configurable [AvailabilityProvider] stand-in for service-level tests.
 *
 * By default it is an enabled, internally pollable provider whose
 * [availability] throws — most callers only need identity and capabilities
 * because a fetcher stub answers instead. Pass [onAvailability] to serve a
 * canned batch or to inject an error; [calls] records every invocation.
 */
class FakeAvailabilityProvider(
    override val id: BookingProvider = BookingProvider.RECGOV,
    supportsInternalPolling: Boolean = true,
    bookingHorizonDays: Int = FAKE_PROVIDER_HORIZON_DAYS,
    maxPollWindowDays: Int = FAKE_PROVIDER_MAX_POLL_WINDOW_DAYS,
    private val enabled: Boolean = true,
    private val parentRefOverride: ((Campground) -> BookingProviderRef?)? = null,
    private val onAvailability: (suspend (FakeAvailabilityCall) -> AvailabilityObservationBatch)? = null,
) : AvailabilityProvider {
    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = supportsInternalPolling,
            bookingHorizonDays = bookingHorizonDays,
            maxPollWindowDays = maxPollWindowDays,
        )

    private val recorded = mutableListOf<FakeAvailabilityCall>()

    val calls: List<FakeAvailabilityCall> get() = recorded.toList()

    override fun isEnabled(): Boolean = enabled

    override fun parentRefFor(campground: Campground): BookingProviderRef? =
        if (parentRefOverride != null) parentRefOverride.invoke(campground) else super.parentRefFor(campground)

    override suspend fun availability(
        campground: Campground,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val call = FakeAvailabilityCall(campground, startDate, endDate)
        recorded += call
        val handler = onAvailability ?: throw UnsupportedOperationException(UNSTUBBED_AVAILABILITY)
        return handler(call)
    }
}
