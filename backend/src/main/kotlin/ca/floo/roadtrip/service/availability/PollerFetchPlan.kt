package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.repo.AvailabilityWatchRepo

internal data class PollerFetchPlan(
    val targets: List<ResolvedAvailabilityTarget>,
    val windowFor: (PoiDateContext, AvailabilityProviderCapabilities) -> AvailabilityWindows?,
    val cadenceSec: Int,
    val liveWatches: List<AvailabilityWatchRepo.Watch>,
)
