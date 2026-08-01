package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

internal fun AvailabilityProviderError.toFetchOutcome(): FetchOutcome =
    when (this) {
        is AvailabilityProviderError.RateLimited -> FetchOutcome.RATE_LIMITED
        is AvailabilityProviderError.UpstreamBlocked -> FetchOutcome.BLOCKED
        is AvailabilityProviderError.UpstreamUnavailable -> FetchOutcome.UPSTREAM_5XX
        // Unreachable is still retryable: fail over to the next candidate
        // rather than dropping to OTHER, which does not trigger failover.
        is AvailabilityProviderError.UpstreamUnreachable -> FetchOutcome.UPSTREAM_5XX
        else -> FetchOutcome.OTHER
    }

internal class CatalogAvailabilityBatcher {
    private data class GroupKey(
        val provider: AvailabilityProvider,
        val campground: Campground,
        val dateContext: PoiDateContext,
    )

    fun countFetchGroups(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, AvailabilityProviderCapabilities) -> AvailabilityWindows?,
    ): Int =
        targets
            .map { GroupKey(it.provider, it.campground, it.dateContext) }
            .distinct()
            .count { windowFor(it.dateContext, it.provider.capabilities) != null }

    fun filterFetchTargets(
        targets: List<ResolvedAvailabilityTarget>,
        windowFor: (PoiDateContext, AvailabilityProviderCapabilities) -> AvailabilityWindows?,
        shouldFetch: (targets: List<ResolvedAvailabilityTarget>, windows: AvailabilityWindows) -> Boolean,
    ): List<ResolvedAvailabilityTarget> =
        targets
            .groupBy { GroupKey(it.provider, it.campground, it.dateContext) }
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
            campground: Campground,
            provider: AvailabilityProvider,
            targets: List<ResolvedAvailabilityTarget>,
            windows: AvailabilityWindows,
        ) -> AvailabilityObservationBatch,
    ): List<GroupFetchResult> =
        targets
            .groupBy { GroupKey(it.provider, it.campground, it.dateContext) }
            .map { (key, groupTargets) ->
                val campsites = groupTargets.map { it.campsite }
                val windows = windowFor(key.dateContext, key.provider.capabilities)
                if (windows == null) {
                    return@map GroupFetchResult(
                        provider = key.provider,
                        campground = key.campground,
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
                    val batch = fetch(key.campground, key.provider, groupTargets, windows)
                    GroupFetchResult(
                        provider = key.provider,
                        campground = key.campground,
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
                        campground = key.campground,
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
