package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
                    status = supercharger.siteStatus,
                    timeZone = supercharger.timeZone,
                    amenities = supercharger.amenities,
                    stallCount = supercharger.stallCount,
                    powerKilowatt = supercharger.maxPowerKw,
                    pricebooks = supercharger.pricebooks,
                    availabilityProfile = supercharger.availabilityProfile,
                    openToNonTeslas = supercharger.openToNonTeslas,
                    trailerFriendly = supercharger.trailerFriendly,
                    twentyFourSeven = supercharger.twentyFourSeven,
                    upstream =
                        buildJsonObject {
                            put("index", supercharger.indexPayload)
                            put("detail", supercharger.detailPayload)
                        },
                ),
        )
    }

    companion object {
        const val POI_TYPE = "tesla_supercharger"
    }
}
