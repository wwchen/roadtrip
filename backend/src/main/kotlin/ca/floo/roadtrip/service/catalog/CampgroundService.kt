package ca.floo.roadtrip.service.catalog

import ca.floo.roadtrip.models.api.PoiCategoryDetailSchema
import ca.floo.roadtrip.models.api.PoiDetailFeatureSchema
import ca.floo.roadtrip.models.api.PoiDetailPropertiesSchema
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.domain.PoiIndexRow
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.service.api.CAMPGROUND_POI_TYPE
import ca.floo.roadtrip.service.api.PoiCta
import ca.floo.roadtrip.service.api.UrlHosts
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

internal class CampgroundService(
    private val repo: CampgroundRepo,
    private val cta: PoiCta = PoiCta.Default,
) {
    fun poiDetailFeature(
        poi: PoiIndexRow,
        dateContext: PoiDateContext,
        availabilitySupported: Boolean,
        availabilityProvider: String?,
    ): PoiDetailFeatureSchema? {
        val detail = repo.findPoiDetailByPoi(poi.id) ?: return null
        val campground = detail.campground
        val raw = Json.parseToJsonElement(detail.propertiesJson)
        val rawObject = raw as? JsonObject ?: JsonObject(emptyMap())
        val infoUrl = campground.links.firstObjectStringProperty(URL_KEY)
        val description = rawObject.stringProperty("description")
        val photoUrl = rawObject.stringProperty("photo_url")
        return PoiDetailFeatureSchema(
            id = poi.id,
            geometry = Json.parseToJsonElement(poi.geomJson),
            properties =
                PoiDetailPropertiesSchema(
                    source = detail.source,
                    sourceId = detail.sourceId,
                    category = CAMPGROUND_POI_TYPE,
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
                            availabilitySupported = availabilitySupported.takeIf { it },
                            cta =
                                cta.computeCta(
                                    providerRefJson = detail.providerRefJson,
                                    ctaProviderRefJson = detail.ctaProviderRefJson,
                                    reserveUrl = campground.reservationUrl,
                                    infoUrl = infoUrl,
                                ),
                            bookingSystem =
                                cta.bookingSystem(
                                    providerRefJson = detail.providerRefJson,
                                    reserveUrl = campground.reservationUrl,
                                    infoUrl = infoUrl,
                                ),
                            raw = raw,
                        ),
                ),
        )
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

private fun JsonElement.firstObjectStringProperty(key: String): String? =
    (this as? JsonArray)
        ?.firstNotNullOfOrNull { element ->
            (element as? JsonObject)
                ?.let { (it[key] as? JsonPrimitive)?.contentOrNull }
                ?.trim()
                ?.takeIf { value -> value.isNotEmpty() }
        }
