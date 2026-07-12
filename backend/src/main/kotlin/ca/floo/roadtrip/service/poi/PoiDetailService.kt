package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.models.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.models.domain.poi.PoiIndexRow

internal interface PoiDetailService {
    val poiType: String

    fun poiDetailProperties(poi: PoiIndexRow): PoiDetailPropertiesSchema?
}
