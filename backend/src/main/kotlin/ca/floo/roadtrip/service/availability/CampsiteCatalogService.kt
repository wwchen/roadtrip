package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.CampsiteSummarySchema
import ca.floo.roadtrip.model.api.PoiCampsitesResponseSchema
import ca.floo.roadtrip.model.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo

internal class CampsiteCatalogService(
    private val providerRefs: CampsiteProviderRepo,
    private val campsitesRepo: CampsiteRepo,
    private val targets: AvailabilityTargetResolver,
) {
    fun campsitesForPoi(
        poiId: Long,
        siteTypes: List<String>,
    ): PoiCampsitesResponseSchema {
        if (!providerRefs.campgroundExists(poiId)) throw AvailabilityServiceError.NotFound
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
            resolved.provider.reservationUrlTemplate(campsite, resolved.parentRef)
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
        providerRef = providerRef,
        tags = tags,
        raw = raw,
    )

internal fun List<CampsiteAvailabilityTarget>.filterBySiteTypes(siteTypes: Collection<String>): List<CampsiteAvailabilityTarget> {
    if (siteTypes.isEmpty()) return this
    val allowed = siteTypes.toSet()
    return filter { it.siteType != null && it.siteType in allowed }
}

private const val CAMPSITE_RESPONSE_TYPE = "campsite"
