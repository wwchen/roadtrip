package ca.floo.roadtrip.service.catalog

import ca.floo.roadtrip.models.domain.PoiDetailRow
import ca.floo.roadtrip.models.domain.PoiIndexRow
import ca.floo.roadtrip.repo.CampgroundRepo

private const val CAMPGROUND_POI_TYPE = "campground"
private const val REGION_KEY = "region"
private const val COUNTRY_KEY = "country"
private const val AGENCY_KEY = "agency"
private const val PHONE_KEY = "phone"
private const val URL_KEY = "url"

internal class CampgroundService(
    private val repo: CampgroundRepo,
) {
    fun poiDetail(poi: PoiIndexRow): PoiDetailRow? {
        val detail = repo.findPoiDetailByPoi(poi.id) ?: return null
        val campground = detail.campground
        return PoiDetailRow(
            id = poi.id,
            source = detail.source,
            sourceId = detail.sourceId,
            category = CAMPGROUND_POI_TYPE,
            subcategory = campground.kind,
            agency = campground.management.stringProperty(AGENCY_KEY),
            name = campground.name,
            region = campground.location.stringProperty(REGION_KEY),
            country = campground.location.stringProperty(COUNTRY_KEY),
            lng = poi.lng,
            lat = poi.lat,
            unitName = null,
            reserveUrl = campground.reservationUrl,
            phone = campground.contact.stringProperty(PHONE_KEY),
            infoUrl = campground.links.firstObjectStringProperty(URL_KEY),
            addressJson = campground.location.toString(),
            providerRefJson = detail.providerRefJson,
            geomJson = poi.geomJson,
            propertiesJson = detail.propertiesJson,
            ctaProviderRefJson = detail.ctaProviderRefJson,
            memberSources = detail.memberSources,
        )
    }
}
