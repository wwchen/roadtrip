package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.CampgroundColumnJson
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import ca.floo.roadtrip.service.poi.campground.UrlHosts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val LAST_UPDATED_KEY = "last_updated"

internal class CampgroundService(
    private val campgroundRepo: CampgroundRepo,
    private val dateResolver: AvailabilityDateResolver,
    private val cta: CampgroundCta = CampgroundCta.default,
) : PoiDetailService {
    override val poiType: String = POI_TYPE

    override fun poiDetailProperties(poi: PoiIndexRow): PoiDetailPropertiesSchema? {
        val detail = campgroundRepo.findPoiDetailByPoi(poi.id) ?: return null
        val campground = detail.campground
        val raw = Json.parseToJsonElement(detail.propertiesJson)
        val infoUrl = campground.links.firstOrNull()?.url
        val description =
            campground.mediumDescription
                ?: campground.shortDescription
                ?: campground.longDescription
        val photoUrl = campground.photos.firstOrNull()?.url
        val dateContext =
            dateResolver.context(
                lat = campground.location?.latitude,
                lng = campground.location?.longitude,
            )
        val availabilityProvider = campground.bookingProvider
        val computedCtas =
            cta.computeCtas(
                providerRefJson = detail.providerRefJson,
                ctaProviderRefJson = detail.ctaProviderRefJson,
                reserveUrl = campground.reservationUrl,
                infoUrl = infoUrl,
            )
        val ctas = computedCtas.takeIf { it.isNotEmpty() }
        return PoiDetailPropertiesSchema(
            source = detail.source,
            sourceId = detail.sourceId,
            category = POI_TYPE,
            subcategory = campground.kind,
            agency = campground.management?.agency,
            name = campground.name,
            region = campground.location?.region,
            country = campground.location?.country,
            detail =
                PoiCategoryDetailSchema(
                    sources = detail.memberSources,
                    availabilityProvider = availabilityProvider,
                    timeZone = dateContext.timeZone.id,
                    earliestDate = dateContext.earliestDate.toString(),
                    unitName = null,
                    reserveUrl = campground.reservationUrl,
                    bookingSite = campground.reservationUrl?.let(UrlHosts::extract),
                    phone = campground.contact?.phone,
                    infoUrl = infoUrl,
                    address = CampgroundColumnJson.element(campground.location),
                    description = description,
                    photoUrl = photoUrl,
                    providerRef = detail.providerRefJson?.let { Json.parseToJsonElement(it) },
                    availabilitySupported = (availabilityProvider != null).takeIf { it },
                    cta = ctas,
                    bookingSystem =
                        cta.bookingSystem(
                            providerRefJson = detail.providerRefJson,
                            reserveUrl = campground.reservationUrl,
                            infoUrl = infoUrl,
                        ),
                    // The source record, sent once — no longer also as `raw`.
                    upstream = raw,
                    status = campground.status,
                    statusDescription = campground.statusDescription,
                    kind = campground.kind,
                    price = campground.price,
                    schedule = campground.defaultCampsiteSchedule,
                    amenities = campground.amenities,
                    cellCoverage = campground.cellService,
                    maxRvLength = campground.maxRvLength,
                    maxTrailerLength = campground.maxTrailerLength,
                    hasPullThroughSites = campground.hasPullThroughSites,
                    bigRigFriendly = campground.bigRigFriendly,
                    links = CampgroundColumnJson.elements(campground.links),
                    alerts = campground.alerts,
                    connections = campground.connections,
                    metadata = campground.metadata,
                    management = CampgroundColumnJson.element(campground.management),
                    contact = CampgroundColumnJson.element(campground.contact),
                    email = campground.contact?.email,
                    elevation = campground.location?.elevation,
                    lastVerified = campground.metadata.stringProperty(LAST_UPDATED_KEY),
                ),
        )
    }

    companion object {
        const val POI_TYPE = "campground"
        const val MIN_POI_ZOOM: Int = 6
    }
}

private fun JsonElement.stringProperty(key: String): String? = stringProperty(listOf(key))

private fun JsonElement.stringProperty(keys: List<String>): String? = (this as? JsonObject)?.firstStringProperty(keys)

private fun JsonObject.firstStringProperty(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
    }
