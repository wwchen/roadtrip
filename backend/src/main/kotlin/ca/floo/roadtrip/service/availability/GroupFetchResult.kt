package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderError
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider

/** Result of one (provider, parentRef, dateContext) group's fetch.
 *  [window] is null when the group had no future dates and was skipped
 *  (no upstream call, no error). [batch] is non-null iff outcome == OK. */
internal data class GroupFetchResult(
    val provider: AvailabilityProvider,
    val parentRef: ProviderRef,
    val dateContext: PoiDateContext,
    val campsites: List<CampsiteAvailabilityTarget>,
    val window: ResolvedDateWindow?,
    val batch: AvailabilityObservationBatch?,
    val outcome: FetchOutcome,
    val durationMs: Int,
    val error: String?,
    val providerError: AvailabilityProviderError? = null,
)
