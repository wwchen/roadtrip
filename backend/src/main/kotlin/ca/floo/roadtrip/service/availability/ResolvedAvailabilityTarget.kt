package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.domain.Campsite
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.CatalogReservableRef

internal data class ResolvedAvailabilityTarget(
    val campsite: Campsite,
    val provider: AvailabilityProvider,
    val parentRef: ProviderRef,
    val catalogRef: CatalogReservableRef,
    val parentPoiId: Long,
    val dateContext: PoiDateContext,
)
