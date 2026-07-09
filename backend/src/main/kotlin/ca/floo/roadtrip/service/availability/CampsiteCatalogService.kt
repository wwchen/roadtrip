package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.api.CampsiteSummarySchema
import ca.floo.roadtrip.models.api.PoiCampsitesResponseSchema
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.service.availability.provider.ProviderRefParser
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraBookingUrl
import ca.floo.roadtrip.service.availability.provider.adapters.aspira.AspiraTenants
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovBookingUrl

internal class CampsiteCatalogService(
    private val providerRefs: CampsiteProviderRepo,
    private val campsitesRepo: CampsiteRepo,
) {
    fun campsitesForPoi(
        poiId: Long,
        siteTypes: List<String>,
    ): PoiCampsitesResponseSchema {
        if (!providerRefs.campgroundExists(poiId)) throw AvailabilityServiceError.NotFound
        val parentRef = providerRefs.findProviderRef(poiId)?.providerRefJson?.let(ProviderRefParser::parse)
        val campsites =
            campsitesRepo
                .findByPoi(poiId)
                .filterBySiteTypes(siteTypes)
        return PoiCampsitesResponseSchema(
            poiId = poiId,
            type = CAMPSITE_RESPONSE_TYPE,
            campsites =
                campsites.map {
                    it.toCampsiteSchema(
                        poiIds = listOf(poiId),
                        reservationUrlTemplate = it.reservationUrlTemplate(parentRef),
                    )
                },
        )
    }
}

internal fun Reservable.toCampsiteSchema(
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

internal fun Reservable.reservationUrlTemplate(parentRef: ProviderRef?): String? =
    when {
        vendor == "recgov" -> RecGovBookingUrl.template(vendorId)
        vendor.startsWith("aspira_") ->
            AspiraTenants.byVendorCode(vendor)?.host?.let { host ->
                AspiraBookingUrl.templateFor(host, providerRef, parentRef)
            }
        else -> null
    }

internal fun List<Reservable>.filterBySiteTypes(siteTypes: Collection<String>): List<Reservable> {
    if (siteTypes.isEmpty()) return this
    val allowed = siteTypes.toSet()
    return filter { it.siteType != null && it.siteType in allowed }
}

private const val CAMPSITE_RESPONSE_TYPE = "campsite"
