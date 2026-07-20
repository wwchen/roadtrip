package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

/**
 * A campsite paired with the ordered availability providers that can serve
 * it. [candidates] is non-empty; its first entry is mirrored onto
 * [provider]/[campground]/[catalogRef] so single-candidate call sites
 * (batcher `GroupKey`, loader, poll executor) stay source-compatible while
 * failover-aware call sites walk [candidates] in order.
 */
internal data class ResolvedAvailabilityTarget(
    val campsite: Campsite,
    val provider: AvailabilityProvider,
    val campground: Campground,
    val catalogRef: CatalogCampsiteRef,
    val parentPoiId: Long,
    val dateContext: PoiDateContext,
    val candidates: List<ProviderCandidate> =
        listOf(ProviderCandidate(provider = provider, campground = campground, catalogRef = catalogRef)),
) {
    val parentRef: BookingProviderRef?
        get() = provider.parentRefFor(campground)

    init {
        require(candidates.isNotEmpty()) { "candidates must be non-empty" }
        val first = candidates.first()
        require(first.provider == provider) { "candidates[0].provider must mirror provider" }
        require(first.campground == campground) { "candidates[0].campground must mirror campground" }
        require(first.catalogRef == catalogRef) { "candidates[0].catalogRef must mirror catalogRef" }
    }
}

internal fun List<ResolvedAvailabilityTarget>.catalogRefsFor(candidate: ProviderCandidate): List<CatalogCampsiteRef> {
    val refs =
        mapNotNull { row ->
            row.candidates
                .firstOrNull { it.provider.id == candidate.provider.id && it.campground == candidate.campground }
                ?.catalogRef
        }
    return refs.takeIf { it.size == this.size } ?: emptyList()
}
