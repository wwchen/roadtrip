package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId

internal data class AvailabilityPollerKey(
    val provider: String,
    val parentRef: String,
)

internal fun pollerKeyFor(
    providerId: AvailabilityProviderId,
    parentRef: ProviderRef,
): AvailabilityPollerKey =
    AvailabilityPollerKey(
        provider = providerId.name.lowercase(),
        parentRef = parentRefKey(parentRef),
    )
