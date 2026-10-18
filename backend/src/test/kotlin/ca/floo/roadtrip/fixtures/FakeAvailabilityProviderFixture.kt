package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** rec.gov's real horizon; the default for fakes that don't care. */
const val FAKE_PROVIDER_HORIZON_DAYS = 180

/** A year out, for fakes standing in for providers that book further ahead. */
const val FAKE_PROVIDER_YEAR_HORIZON_DAYS = 365

const val FAKE_PROVIDER_MAX_POLL_WINDOW_DAYS = 60

private const val UNSTUBBED_AVAILABILITY = "FakeAvailabilityProvider.availability was not stubbed for this test"

/** One recorded [FakeAvailabilityProvider.availability] call. */
data class FakeAvailabilityCall(
    val campground: Campground,
    /** The campsites the call was asked to cover. */
    val campsites: List<Campsite>,
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
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val call = FakeAvailabilityCall(campground, campsites, startDate, endDate)
        recorded += call
        val handler = onAvailability ?: throw UnsupportedOperationException(UNSTUBBED_AVAILABILITY)
        return handler(call)
    }
}

/**
 * What an adapter answers: one status per `(campsite, night)` for every campsite
 * the call was given, each keyed — the base-unit shape the port requires. Every
 * shipping adapter fetches its campground whatever the caller filtered to, so
 * this is also why a filtered read warms the whole campground.
 */
fun campgroundBatch(
    call: FakeAvailabilityCall,
    provider: BookingProvider,
    status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    observedAt: Instant,
): AvailabilityObservationBatch =
    AvailabilityObservationBatch(
        provider = provider.id,
        startDate = call.startDate,
        endDate = call.endDate,
        observations =
            call.campsites.flatMap { campsite ->
                datesOf(call.startDate, call.endDate).map { date ->
                    CampsiteDayObservation(campsiteId = campsite.id, date = date, observedAt = observedAt, status = status)
                }
            },
        cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
    )

private fun datesOf(
    startDate: LocalDate,
    endDate: LocalDate,
): List<LocalDate> =
    (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt())
        .map { startDate.plusDays(it.toLong()) }
