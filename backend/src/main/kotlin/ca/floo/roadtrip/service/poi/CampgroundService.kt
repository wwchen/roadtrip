package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.BookingRefDto
import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.domain.CatalogColumnJson
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import ca.floo.roadtrip.service.poi.campground.UrlHosts
import kotlinx.serialization.json.Json

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
                bookingRef = detail.bookingRef,
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
                    address = CatalogColumnJson.element(campground.location),
                    description = description,
                    photoUrl = photoUrl,
                    bookingRef = detail.bookingRef?.let(BookingRefDto::from),
                    availabilitySupported = (detail.bookingRef != null).takeIf { it },
                    cta = ctas,
                    bookingSystem =
                        cta.bookingSystem(
                            bookingRef = detail.bookingRef,
                            reserveUrl = campground.reservationUrl,
                            infoUrl = infoUrl,
                        ),
                    // The source record, sent once — no longer also as `raw`.
                    upstream = raw,
                    status = campground.status,
                    statusDescription = campground.statusDescription,
                    kind = campground.kind,
                    price = CatalogColumnJson.element(campground.price),
                    schedule = CatalogColumnJson.element(campground.defaultCampsiteSchedule),
                    amenities = CatalogColumnJson.elements(campground.amenities),
                    cellCoverage = CatalogColumnJson.elements(campground.cellService),
                    maxRvLength = campground.maxRvLength,
                    maxTrailerLength = campground.maxTrailerLength,
                    hasPullThroughSites = campground.hasPullThroughSites,
                    bigRigFriendly = campground.bigRigFriendly,
                    links = CatalogColumnJson.elements(campground.links),
                    alerts = CatalogColumnJson.elements(campground.alerts),
                    connections = campground.connections,
                    metadata = CatalogColumnJson.element(campground.metadata),
                    management = CatalogColumnJson.element(campground.management),
                    contact = CatalogColumnJson.element(campground.contact),
                    email = campground.contact?.email,
                    elevation = campground.location?.elevation,
                    lastVerified =
                        campground.metadata
                            ?.lastUpdated
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                ),
        )
    }

    companion object {
        const val POI_TYPE = "campground"
        const val MIN_POI_ZOOM: Int = 6
    }
}
