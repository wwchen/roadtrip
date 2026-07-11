package ca.floo.roadtrip.service.catalog

import ca.floo.roadtrip.models.domain.PoiDetailRow
import ca.floo.roadtrip.models.domain.PoiIndexRow
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo

private const val TESLA_SUPERCHARGER_POI_TYPE = "tesla_supercharger"

internal class TeslaSuperchargerService(
    private val repo: TeslaSuperchargerRepo,
) {
    fun poiDetail(poi: PoiIndexRow): PoiDetailRow? {
        val detail = repo.findPoiDetailByPoi(poi.id) ?: return null
        val supercharger = detail.supercharger
        return PoiDetailRow(
            id = poi.id,
            source = TESLA_SUPERCHARGER_POI_TYPE,
            sourceId = supercharger.locationSlug,
            category = TESLA_SUPERCHARGER_POI_TYPE,
            subcategory = null,
            name = supercharger.commonSiteName,
            region = supercharger.region,
            country = supercharger.country,
            lng = poi.lng,
            lat = poi.lat,
            unitName = null,
            reserveUrl = null,
            phone = null,
            infoUrl = supercharger.infoUrl,
            addressJson = supercharger.address.toString(),
            geomJson = poi.geomJson,
            propertiesJson = detail.propertiesJson,
        )
    }
}
