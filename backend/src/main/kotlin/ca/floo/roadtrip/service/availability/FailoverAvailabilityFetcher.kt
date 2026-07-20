package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.Instant

/**
 * Walks the resolver-provided candidate list until one candidate returns a
 * batch. Retryable failures (rate-limited, upstream 5xx, blocked) fail over to
 * the next candidate; anything else stops the walk immediately.
 *
 * The fetcher is deliberately I/O-shaped: it captures one [AttemptRecord] per
 * upstream call so the caller can persist a trace row for every attempt,
 * regardless of the terminal outcome. Cooldown side-effects are booked here
 * (via [ProviderCooldownTracker.recordSuccess] / [ProviderCooldownTracker.recordFailure])
 * so callers don't have to remember to.
 *
 * Ref translation is delegated: the caller supplies `translateRefs`, which
 * returns the provider-specific campsite refs for the candidate being tried.
 * This keeps observations anchored to the catalog campsite ids chosen by the
 * resolver.
 */
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
        /** Non-null iff some attempt returned OK. */
        val batch: AvailabilityObservationBatch?,
        /** Null iff no attempt returned OK. */
        val servedBy: BookingProvider?,
        /** One record per upstream call the fetcher issued, in walk order. */
        val attempts: List<AttemptRecord>,
    )

    open suspend fun fetch(
        candidates: List<ProviderCandidate>,
        @Suppress("UNUSED_PARAMETER") campsites: List<Campsite>,
        window: ResolvedDateWindow,
        translateRefs: (ProviderCandidate) -> List<CatalogCampsiteRef>,
    ): FailoverResult {
        val sorted = cooldowns.sortHealthyFirst(candidates) { it.provider.id }
        if (sorted.isEmpty()) return FailoverResult(batch = null, servedBy = null, attempts = emptyList())

        val attempts = mutableListOf<AttemptRecord>()
        for (candidate in sorted) {
            val providerId = candidate.provider.id
            val parentRef = candidate.parentRef
            val refs = translateRefs(candidate)
            if (refs.isEmpty()) {
                attempts +=
                    AttemptRecord(
                        provider = providerId,
                        parentRef = parentRef,
                        outcome = FetchOutcome.OTHER,
                        durationMs = 0,
                        error = NO_REFS_ERROR,
                    )
                // Data-consistency issue for this vendor row — not a transient
                // failure. Don't cool the provider off, but stop the walk.
                return FailoverResult(batch = null, servedBy = null, attempts = attempts)
            }

            val begin = clock()
            val attempt = attemptFetch(candidate, refs, window)
            val durationMs = elapsedMs(begin, clock())

            attempts +=
                AttemptRecord(
                    provider = providerId,
                    parentRef = parentRef,
                    outcome = attempt.outcome,
                    durationMs = durationMs,
                    error = attempt.error,
                    providerError = attempt.providerError,
                )

            when (attempt.outcome) {
                FetchOutcome.OK -> {
                    cooldowns.recordSuccess(providerId)
                    return FailoverResult(batch = attempt.batch, servedBy = providerId, attempts = attempts)
                }
                FetchOutcome.RATE_LIMITED,
                FetchOutcome.UPSTREAM_5XX,
                FetchOutcome.BLOCKED,
                -> {
                    cooldowns.recordFailure(providerId)
                    // fall through to next candidate
                }
                FetchOutcome.OTHER -> {
                    // Non-retryable — stop the walk with what we have.
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
        candidate: ProviderCandidate,
        refs: List<CatalogCampsiteRef>,
        window: ResolvedDateWindow,
    ): Attempt =
        try {
            val batch =
                candidate.provider.catalogAvailability(
                    campground = candidate.campground,
                    campsites = refs,
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

    companion object {
        internal const val NO_REFS_ERROR = "no campsite refs for candidate"
    }
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
