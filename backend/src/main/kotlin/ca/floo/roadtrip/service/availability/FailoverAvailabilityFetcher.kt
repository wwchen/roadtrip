package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import java.time.Instant

internal open class FailoverAvailabilityFetcher(
    private val cooldowns: ProviderCooldownTracker,
    private val clock: () -> Instant = Instant::now,
) {
    data class AttemptRecord(
        val provider: BookingProvider,
        val parentRef: BookingProviderRef?,
        val outcome: FetchOutcome,
        val durationMs: Int,
        val error: String?,
        val providerError: AvailabilityProviderError? = null,
    )

    data class FailoverResult(
        val batch: AvailabilityObservationBatch?,
        val servedBy: BookingProvider?,
        val attempts: List<AttemptRecord>,
    )

    open suspend fun fetch(
        providers: List<AvailabilityProvider>,
        campground: Campground,
        campsites: List<Campsite>,
        window: ResolvedDateWindow,
    ): FailoverResult {
        val sorted = cooldowns.sortHealthyFirst(providers) { it.id }
        if (sorted.isEmpty()) return FailoverResult(batch = null, servedBy = null, attempts = emptyList())

        val attempts = mutableListOf<AttemptRecord>()
        for (provider in sorted) {
            val begin = clock()
            val attempt = attemptFetch(provider, campground, campsites, window)
            val durationMs = elapsedMs(begin, clock())

            attempts +=
                AttemptRecord(
                    provider = provider.id,
                    parentRef = provider.parentRefFor(campground),
                    outcome = attempt.outcome,
                    durationMs = durationMs,
                    error = attempt.error,
                    providerError = attempt.providerError,
                )

            when (attempt.outcome) {
                FetchOutcome.OK -> {
                    cooldowns.recordSuccess(provider.id)
                    return FailoverResult(batch = attempt.batch, servedBy = provider.id, attempts = attempts)
                }
                FetchOutcome.RATE_LIMITED,
                FetchOutcome.UPSTREAM_5XX,
                FetchOutcome.BLOCKED,
                -> {
                    cooldowns.recordFailure(provider.id)
                }
                FetchOutcome.OTHER -> {
                    return FailoverResult(batch = null, servedBy = null, attempts = attempts)
                }
            }
        }
        return FailoverResult(batch = null, servedBy = null, attempts = attempts)
    }

    private data class Attempt(
        val outcome: FetchOutcome,
        val batch: AvailabilityObservationBatch?,
        val error: String?,
        val providerError: AvailabilityProviderError? = null,
    )

    private suspend fun attemptFetch(
        provider: AvailabilityProvider,
        campground: Campground,
        campsites: List<Campsite>,
        window: ResolvedDateWindow,
    ): Attempt =
        try {
            val batch =
                provider.catalogAvailability(
                    campground = campground,
                    campsites = campsites,
                    startDate = window.startDate,
                    endDate = window.endDate,
                )
            Attempt(outcome = FetchOutcome.OK, batch = batch, error = null)
        } catch (e: AvailabilityProviderError) {
            Attempt(outcome = e.toFetchOutcome(), batch = null, error = e.message ?: e::class.simpleName, providerError = e)
        } catch (e: Throwable) {
            Attempt(outcome = FetchOutcome.OTHER, batch = null, error = e.message ?: e.javaClass.simpleName)
        }

    private fun elapsedMs(
        begin: Instant,
        end: Instant,
    ): Int = (end.toEpochMilli() - begin.toEpochMilli()).toInt().coerceAtLeast(0)
}

private const val NO_AVAILABILITY_CANDIDATES_ERROR = "no availability candidates available"

internal fun availabilityProviderErrorFromAttempt(last: FailoverAvailabilityFetcher.AttemptRecord?): AvailabilityProviderError {
    last?.providerError?.let { return it }
    val message = last?.error ?: NO_AVAILABILITY_CANDIDATES_ERROR
    val cause = RuntimeException(message)
    return when (last?.outcome) {
        FetchOutcome.RATE_LIMITED -> AvailabilityProviderError.RateLimited(cause)
        FetchOutcome.BLOCKED -> AvailabilityProviderError.UpstreamBlocked(cause)
        FetchOutcome.UPSTREAM_5XX,
        FetchOutcome.OK,
        FetchOutcome.OTHER,
        null,
        -> AvailabilityProviderError.UpstreamUnavailable(cause)
    }
}
