package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

/**
 * A campsite paired with the ordered availability providers that can serve
 * it. [candidates] is non-empty; its first entry is mirrored onto
 * [provider]/[parentRef]/[catalogRef] so single-candidate call sites
 * (batcher `GroupKey`, loader, poll executor) stay source-compatible while
 * failover-aware call sites walk [candidates] in order.
 */
internal data class ResolvedAvailabilityTarget(
    val campsite: CampsiteAvailabilityTarget,
    val provider: AvailabilityProvider,
    val parentRef: ProviderRef,
    val catalogRef: CatalogCampsiteRef,
    val parentPoiId: Long,
    val dateContext: PoiDateContext,
    val candidates: List<ProviderCandidate> =
        listOf(ProviderCandidate(provider = provider, parentRef = parentRef, catalogRef = catalogRef)),
) {
    init {
        require(candidates.isNotEmpty()) { "candidates must be non-empty" }
        val first = candidates.first()
        require(first.provider == provider) { "candidates[0].provider must mirror provider" }
        require(first.parentRef == parentRef) { "candidates[0].parentRef must mirror parentRef" }
        require(first.catalogRef == catalogRef) { "candidates[0].catalogRef must mirror catalogRef" }
    }
}

internal fun ResolvedAvailabilityTarget.internalPollingTarget(): ResolvedAvailabilityTarget? {
    val pollableCandidates = candidates.filter { it.provider.capabilities.supportsInternalPolling }
    val head = pollableCandidates.firstOrNull() ?: return null
    return withPreferredCandidate(head, pollableCandidates)
}

internal fun ResolvedAvailabilityTarget.withPreferredCandidate(
    candidate: ProviderCandidate,
    candidates: List<ProviderCandidate>,
): ResolvedAvailabilityTarget =
    copy(
        provider = candidate.provider,
        parentRef = candidate.parentRef,
        catalogRef = candidate.catalogRef,
        candidates = candidates,
    )
