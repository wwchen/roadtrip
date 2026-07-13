package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderError
import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
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
        val provider: AvailabilityProviderId,
        val parentRef: ProviderRef,
        val outcome: FetchOutcome,
        val durationMs: Int,
        val error: String?,
    )

    data class FailoverResult(
        /** Non-null iff some attempt returned OK. */
        val batch: AvailabilityObservationBatch?,
        /** Null iff no attempt returned OK. */
        val servedBy: AvailabilityProviderId?,
        /** One record per upstream call the fetcher issued, in walk order. */
        val attempts: List<AttemptRecord>,
    )

    open suspend fun fetch(
        candidates: List<ProviderCandidate>,
        @Suppress("UNUSED_PARAMETER") campsites: List<CampsiteAvailabilityTarget>,
        window: ResolvedDateWindow,
        translateRefs: (ProviderCandidate) -> List<CatalogCampsiteRef>,
    ): FailoverResult {
        val sorted = cooldowns.sortHealthyFirst(candidates) { it.provider.id }
        if (sorted.isEmpty()) return FailoverResult(batch = null, servedBy = null, attempts = emptyList())

        val attempts = mutableListOf<AttemptRecord>()
        for (candidate in sorted) {
            val providerId = candidate.provider.id
            val refs = translateRefs(candidate)
            if (refs.isEmpty()) {
                attempts +=
                    AttemptRecord(
                        provider = providerId,
                        parentRef = candidate.parentRef,
                        outcome = FetchOutcome.OTHER,
                        durationMs = 0,
                        error = NO_REFS_ERROR,
                    )
                // Data-consistency issue for this vendor row — not a transient
                // failure. Don't cool the provider off, but stop the walk.
                return FailoverResult(batch = null, servedBy = null, attempts = attempts)
            }

            val begin = clock()
            val (outcome, batch, error) = attemptFetch(candidate, refs, window)
            val durationMs = elapsedMs(begin, clock())

            attempts +=
                AttemptRecord(
                    provider = providerId,
                    parentRef = candidate.parentRef,
                    outcome = outcome,
                    durationMs = durationMs,
                    error = error,
                )

            when (outcome) {
                FetchOutcome.OK -> {
                    cooldowns.recordSuccess(providerId)
                    return FailoverResult(batch = batch, servedBy = providerId, attempts = attempts)
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
    )

    private suspend fun attemptFetch(
        candidate: ProviderCandidate,
        refs: List<CatalogCampsiteRef>,
        window: ResolvedDateWindow,
    ): Attempt =
        try {
            val batch =
                candidate.provider.catalogAvailability(
                    ref = candidate.parentRef,
                    campsites = refs,
                    startDate = window.startDate,
                    endDate = window.endDate,
                )
            Attempt(outcome = FetchOutcome.OK, batch = batch, error = null)
        } catch (e: AvailabilityProviderError) {
            Attempt(outcome = e.toFetchOutcome(), batch = null, error = e.message ?: e::class.simpleName)
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
