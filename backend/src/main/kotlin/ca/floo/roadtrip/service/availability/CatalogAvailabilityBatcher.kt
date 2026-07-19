package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

internal fun AvailabilityProviderError.toFetchOutcome(): FetchOutcome =
    when (this) {
        is AvailabilityProviderError.RateLimited -> FetchOutcome.RATE_LIMITED
        is AvailabilityProviderError.UpstreamBlocked -> FetchOutcome.BLOCKED
        is AvailabilityProviderError.UpstreamUnavailable -> FetchOutcome.UPSTREAM_5XX
        else -> FetchOutcome.OTHER
    }

internal class CatalogAvailabilityBatcher {
    private data class GroupKey(
        val provider: AvailabilityProvider,
        val parentRef: BookingProviderRef,
        val dateContext: PoiDateContext,
    )

    /**
     * How many distinct (provider, parentRef, dateContext) groups [targets]
     * would produce a REAL upstream call for — i.e. groups whose polling
     * window ([windowFor]) is non-null. Groups with a null window are skipped
     * by [fetchByGroup] (all target dates elapsed: no upstream call, no error),
     * so they must NOT be counted for the governor: charging a token for a
     * non-fetch wastes it and can needlessly starve a bucket, delaying the
     * retirement of an all-elapsed poller.
     *
     * The governor consumes one vendor token per REAL fetch group, so this is
     * the token count the executor must acquire before fetching. Uses the same
     * [GroupKey] and the same [windowFor] as [fetchByGroup] so the two never
     * drift on either the grouping key or the skip decision.
     */
    fun countFetchGroups(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, AvailabilityProviderCapabilities) -> AvailabilityWindows?,
    ): Int =
        targets
            .map { GroupKey(it.provider, it.parentRef, it.dateContext) }
            .distinct()
            .count { windowFor(it.dateContext, it.provider.capabilities) != null }

    fun filterFetchTargets(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, AvailabilityProviderCapabilities) -> AvailabilityWindows?,
        shouldFetch: (targets: List<ResolvedAvailabilityTarget>, windows: AvailabilityWindows) -> Boolean,
    ): List<ResolvedAvailabilityTarget> =
        targets
            .groupBy { GroupKey(it.provider, it.parentRef, it.dateContext) }
            .values
            .filter { groupTargets ->
                val first = groupTargets.firstOrNull() ?: return@filter false
                val windows = windowFor(first.dateContext, first.provider.capabilities) ?: return@filter false
                shouldFetch(groupTargets, windows)
            }.flatten()

    suspend fun fetchByGroup(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, AvailabilityProviderCapabilities) -> AvailabilityWindows?,
        fetch: suspend (
            parentRef: BookingProviderRef,
            provider: AvailabilityProvider,
            targets: List<ResolvedAvailabilityTarget>,
            windows: AvailabilityWindows,
        ) -> AvailabilityObservationBatch,
    ): List<GroupFetchResult> =
        targets
            .groupBy { GroupKey(it.provider, it.parentRef, it.dateContext) }
            .map { (key, groupTargets) ->
                val campsites = groupTargets.map { it.campsite }
                val windows = windowFor(key.dateContext, key.provider.capabilities)
                if (windows == null) {
                    return@map GroupFetchResult(
                        provider = key.provider,
                        parentRef = key.parentRef,
                        dateContext = key.dateContext,
                        campsites = campsites,
                        window = null,
                        batch = null,
                        outcome = FetchOutcome.OK,
                        durationMs = 0,
                        error = null,
                    )
                }
                val startedNanos = System.nanoTime()
                try {
                    val batch = fetch(key.parentRef, key.provider, groupTargets, windows)
                    GroupFetchResult(
                        provider = key.provider,
                        parentRef = key.parentRef,
                        dateContext = key.dateContext,
                        campsites = campsites,
                        window = windows.fetch,
                        batch = batch,
                        outcome = FetchOutcome.OK,
                        durationMs = elapsedMs(startedNanos),
                        error = null,
                    )
                } catch (e: AvailabilityProviderError) {
                    GroupFetchResult(
                        provider = key.provider,
                        parentRef = key.parentRef,
                        dateContext = key.dateContext,
                        campsites = campsites,
                        window = windows.fetch,
                        batch = null,
                        outcome = e.toFetchOutcome(),
                        durationMs = elapsedMs(startedNanos),
                        error = e.message ?: e::class.simpleName,
                        providerError = e,
                    )
                }
            }

    private fun elapsedMs(startedNanos: Long): Int = ((System.nanoTime() - startedNanos) / 1_000_000).toInt().coerceAtLeast(0)
}
