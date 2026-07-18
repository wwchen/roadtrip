package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import kotlinx.serialization.json.Json

internal class TeslaSuperchargerService(
    private val teslaSuperchargerRepo: TeslaSuperchargerRepo,
) : PoiDetailService {
    override val poiType: String = POI_TYPE

    override fun poiDetailProperties(poi: PoiIndexRow): PoiDetailPropertiesSchema? {
        val detail = teslaSuperchargerRepo.findPoiDetailByPoi(poi.id) ?: return null
        val supercharger = detail.supercharger
        val raw = Json.parseToJsonElement(detail.propertiesJson)
        return PoiDetailPropertiesSchema(
            source = POI_TYPE,
            sourceId = supercharger.locationSlug,
            category = POI_TYPE,
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

    companion object {
        const val POI_TYPE = "tesla_supercharger"
    }
}
