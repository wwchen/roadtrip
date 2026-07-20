package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.PoiCampsitesResponseSchema
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.ref.RefResolver
import ca.floo.roadtrip.service.ref.RefValue
import ca.floo.roadtrip.service.ref.resolve

internal class CampsiteCatalogService(
    private val refResolver: RefResolver,
    private val campsitesRepo: CampsiteRepo,
    private val targets: AvailabilityTargetResolver,
) {
    fun campsitesForPoi(
        poiId: Long,
        siteTypes: List<String>,
    ): PoiCampsitesResponseSchema {
        val campgrounds = refResolver.resolve<RefValue.CampgroundId>(RefValue.PoiId(poiId))
        if (campgrounds.isEmpty()) throw AvailabilityServiceError.NotFound
        val campsites =
            campsitesRepo
                .findByPoi(poiId)
                .filterBySiteTypes(siteTypes)
        return PoiCampsitesResponseSchema(
            poiId = poiId,
            type = CAMPSITE_RESPONSE_TYPE,
            campsites = campsites,
            reservationUrlTemplates =
                campsites
                    .mapNotNull { campsite ->
                        reservationUrlTemplate(campsite)?.let { campsite.id to it }
                    }.toMap(),
        )
    }

    private fun reservationUrlTemplate(campsite: Campsite): String? =
        targets.resolve(campsite)?.let { resolved ->
            resolved.parentRef?.let { ref ->
                resolved.provider.reservationUrlTemplate(campsite, ref)
            }
        }
}

internal fun List<Campsite>.filterBySiteTypes(siteTypes: Collection<String>): List<Campsite> {
    if (siteTypes.isEmpty()) return this
    val allowed = siteTypes.toSet()
    return filter { it.kind in allowed }
}

internal fun Campsite.catalogVendor(): String = dataProviderRef.provider.id

internal fun Campsite.catalogVendorId(): String = dataProviderRef.serialize()

internal fun Campsite.displayName(): String = name.ifBlank { "$SITE_LABEL_PREFIX${catalogVendorId()}" }

private const val CAMPSITE_RESPONSE_TYPE = "campsite"
private const val SITE_LABEL_PREFIX = "Site #"
