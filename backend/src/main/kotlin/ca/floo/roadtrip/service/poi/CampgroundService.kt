package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.CampgroundAvailabilitySupport
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import ca.floo.roadtrip.service.poi.campground.UrlHosts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val REGION_KEY = "region"
private const val COUNTRY_KEY = "country"
private const val AGENCY_KEY = "agency"
private const val PHONE_KEY = "phone"
private const val URL_KEY = "url"
private const val LATITUDE_KEY = "latitude"
private const val LONGITUDE_KEY = "longitude"

internal class CampgroundService(
    private val campgroundRepo: CampgroundRepo,
    private val dateResolver: AvailabilityDateResolver = AvailabilityDateResolver(),
    private val availabilitySupport: CampgroundAvailabilitySupport? = null,
    private val cta: CampgroundCta = CampgroundCta.default,
) : PoiDetailService {
    override val poiType: String = POI_TYPE

    override fun poiDetailProperties(poi: PoiIndexRow): PoiDetailPropertiesSchema? {
        val detail = campgroundRepo.findPoiDetailByPoi(poi.id) ?: return null
        val campground = detail.campground
        val raw = Json.parseToJsonElement(detail.propertiesJson)
        val rawObject = raw as? JsonObject ?: JsonObject(emptyMap())
        val infoUrl = campground.links.firstObjectStringProperty(URL_KEY)
        val description = rawObject.stringProperty("description")
        val photoUrl = rawObject.stringProperty("photo_url")
        val dateContext =
            dateResolver.context(
                lat = campground.location.doubleProperty(LATITUDE_KEY),
                lng = campground.location.doubleProperty(LONGITUDE_KEY),
            )
        val availabilityProvider = availabilitySupport?.preferredAvailabilityProvider(campground.id)
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
            agency = campground.management.stringProperty(AGENCY_KEY),
            name = campground.name,
            region = campground.location.stringProperty(REGION_KEY),
            country = campground.location.stringProperty(COUNTRY_KEY),
            detail =
                PoiCategoryDetailSchema(
                    sources = detail.memberSources,
                    availabilityProvider = availabilityProvider,
                    timeZone = dateContext.timeZone.id,
                    earliestDate = dateContext.earliestDate.toString(),
                    unitName = null,
                    reserveUrl = campground.reservationUrl,
                    bookingSite = campground.reservationUrl?.let(UrlHosts::extract),
                    phone = campground.contact.stringProperty(PHONE_KEY),
                    infoUrl = infoUrl,
                    address = campground.location,
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
                    raw = raw,
                ),
        )
    }

    companion object {
        const val POI_TYPE = "campground"
        const val MIN_POI_ZOOM: Int = 6
    }
}

private fun JsonObject.stringProperty(key: String): String? =
    (this[key] as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonElement.stringProperty(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonElement.doubleProperty(key: String): Double? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.toDoubleOrNull()

private fun JsonElement.firstObjectStringProperty(key: String): String? =
    (this as? JsonArray)
        ?.firstNotNullOfOrNull { element ->
            (element as? JsonObject)
                ?.let { (it[key] as? JsonPrimitive)?.contentOrNull }
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
        }
