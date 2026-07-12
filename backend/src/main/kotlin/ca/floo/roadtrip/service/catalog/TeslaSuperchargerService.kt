package ca.floo.roadtrip.service.catalog

import ca.floo.roadtrip.models.api.PoiCategoryDetailSchema
import ca.floo.roadtrip.models.api.PoiDetailPropertiesSchema
import ca.floo.roadtrip.models.domain.PoiIndexRow
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.service.api.TESLA_SUPERCHARGER_POI_TYPE
import kotlinx.serialization.json.Json

internal class TeslaSuperchargerService(
    private val repo: TeslaSuperchargerRepo,
) {
    fun poiDetailProperties(poi: PoiIndexRow): PoiDetailPropertiesSchema? {
        val detail = repo.findPoiDetailByPoi(poi.id) ?: return null
        val supercharger = detail.supercharger
        val raw = Json.parseToJsonElement(detail.propertiesJson)
        return PoiDetailPropertiesSchema(
            source = TESLA_SUPERCHARGER_POI_TYPE,
            sourceId = supercharger.locationSlug,
            category = TESLA_SUPERCHARGER_POI_TYPE,
            name = supercharger.commonSiteName,
            region = supercharger.region,
            country = supercharger.country,
            detail =
                PoiCategoryDetailSchema(
                    infoUrl = supercharger.infoUrl,
                    address = supercharger.address,
                    raw = raw,
                ),
        )
    }
}
