package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.CampsiteSummarySchema
import ca.floo.roadtrip.model.api.PoiCampsitesResponseSchema
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
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
                .findAvailabilityTargetsByPoi(poiId)
                .filterBySiteTypes(siteTypes)
        return PoiCampsitesResponseSchema(
            poiId = poiId,
            type = CAMPSITE_RESPONSE_TYPE,
            campsites =
                campsites.map {
                    it.toCampsiteSchema(
                        poiIds = listOf(poiId),
                        reservationUrlTemplate = reservationUrlTemplate(it),
                    )
                },
        )
    }

    private fun reservationUrlTemplate(campsite: CampsiteAvailabilityTarget): String? =
        targets.resolve(campsite)?.let { resolved ->
            resolved.provider.reservationUrlTemplate(
                campsite,
                resolved.parentRef,
                catalogMapId = resolved.catalogRef.mapId,
                catalogResourceLocationId = resolved.catalogRef.resourceLocationId,
            )
        }
}

internal fun CampsiteAvailabilityTarget.toCampsiteSchema(
    poiIds: List<Long> = emptyList(),
    reservationUrlTemplate: String? = null,
): CampsiteSummarySchema =
    CampsiteSummarySchema(
        id = id,
        vendor = vendor,
        vendorId = vendorId,
        name = name,
        loop = loop,
        kind = siteType,
        siteType = siteType,
        reservationUrlTemplate = reservationUrlTemplate,
        poiIds = poiIds,
        tags = tags,
        raw = raw,
    )

internal fun List<CampsiteAvailabilityTarget>.filterBySiteTypes(siteTypes: Collection<String>): List<CampsiteAvailabilityTarget> {
    if (siteTypes.isEmpty()) return this
    val allowed = siteTypes.toSet()
    return filter { it.siteType != null && it.siteType in allowed }
}

private const val CAMPSITE_RESPONSE_TYPE = "campsite"
