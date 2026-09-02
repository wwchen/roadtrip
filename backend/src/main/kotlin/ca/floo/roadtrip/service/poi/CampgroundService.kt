package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
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
private const val EMAIL_KEY = "email"
private const val ELEVATION_KEY = "elevation"
private const val LAST_UPDATED_KEY = "last_updated"

// Campflare's photo/contact shape differs from recgov's: recgov writes `url`
// and `contact.phone`; Campflare writes `{original,small,medium,large}_url`
// and `contact.primary_phone` / `contact.primary_email`. These preference
// lists try every vendor's key so no source silently serves null.
private const val LARGE_URL_KEY = "large_url"
private const val MEDIUM_URL_KEY = "medium_url"
private const val SMALL_URL_KEY = "small_url"
private const val ORIGINAL_URL_KEY = "original_url"
private const val PRIMARY_PHONE_KEY = "primary_phone"
private const val PRIMARY_EMAIL_KEY = "primary_email"
private val photoUrlKeys = listOf(URL_KEY, LARGE_URL_KEY, MEDIUM_URL_KEY, SMALL_URL_KEY, ORIGINAL_URL_KEY)
private val phoneKeys = listOf(PHONE_KEY, PRIMARY_PHONE_KEY)
private val emailKeys = listOf(EMAIL_KEY, PRIMARY_EMAIL_KEY)

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
        val infoUrl = campground.links.firstObjectStringProperty(URL_KEY)
        val description =
            campground.mediumDescription
                ?: campground.shortDescription
                ?: campground.longDescription
        val photoUrl = campground.photos.firstObjectStringProperty(photoUrlKeys)
        val dateContext =
            dateResolver.context(
                lat = campground.location.doubleProperty(LATITUDE_KEY),
                lng = campground.location.doubleProperty(LONGITUDE_KEY),
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
                    phone = campground.contact.stringProperty(phoneKeys),
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
                    links = campground.links,
                    alerts = campground.alerts,
                    connections = campground.connections,
                    metadata = campground.metadata,
                    management = campground.management,
                    contact = campground.contact,
                    email = campground.contact.stringProperty(emailKeys),
                    elevation = campground.location.doubleProperty(ELEVATION_KEY),
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

private fun JsonElement.doubleProperty(key: String): Double? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.toDoubleOrNull()

private fun JsonElement.firstObjectStringProperty(key: String): String? = firstObjectStringProperty(listOf(key))

private fun JsonElement.firstObjectStringProperty(keys: List<String>): String? =
    (this as? JsonArray)
        ?.firstNotNullOfOrNull { element -> (element as? JsonObject)?.firstStringProperty(keys) }

private fun JsonObject.firstStringProperty(keys: List<String>): String? =
    keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
    }
