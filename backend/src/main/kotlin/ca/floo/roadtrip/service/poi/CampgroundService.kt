package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.BookingRefDto
import ca.floo.roadtrip.model.api.poi.AlertDto
import ca.floo.roadtrip.model.api.poi.AmenityDto
import ca.floo.roadtrip.model.api.poi.CarrierSignalDto
import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailPropertiesSchema
import ca.floo.roadtrip.model.api.poi.PriceDto
import ca.floo.roadtrip.model.api.poi.RatingDto
import ca.floo.roadtrip.model.api.poi.ScheduleDto
import ca.floo.roadtrip.model.domain.CatalogColumnJson
import ca.floo.roadtrip.model.domain.poi.PoiIndexRow
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.BookingHorizonResolver
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import ca.floo.roadtrip.service.poi.campground.UrlHosts
import kotlinx.serialization.json.Json

internal class CampgroundService(
    private val campgroundRepo: CampgroundRepo,
    private val dateResolver: AvailabilityDateResolver,
    private val bookingHorizons: BookingHorizonResolver,
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
        // The identity the pin actually books through. An aliased row (Campflare
        // primary, rec.gov alias) reads as rec.gov's here, so the ref, the CTA,
        // and the footer label all name the vendor that serves it. With no
        // registered provider claiming the row, its own declared primary is the
        // honest fallback.
        val bookingRef = bookingHorizons.servingProvider(campground)?.parentRefFor(campground) ?: detail.bookingRef
        val computedCtas =
            cta.computeCtas(
                bookingRef = bookingRef,
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
                    latestDate = bookingHorizons.latestDate(campground, dateContext)?.toString(),
                    unitName = null,
                    reserveUrl = campground.reservationUrl,
                    bookingSite = campground.reservationUrl?.let(UrlHosts::extract),
                    phone = campground.contact?.phone,
                    infoUrl = infoUrl,
                    address = CatalogColumnJson.element(campground.location),
                    description = description,
                    photoUrl = photoUrl,
                    bookingRef = bookingRef?.let(BookingRefDto::from),
                    availabilitySupported = (bookingRef != null).takeIf { it },
                    cta = ctas,
                    bookingSystem =
                        cta.bookingSystem(
                            bookingRef = bookingRef,
                            reserveUrl = campground.reservationUrl,
                            infoUrl = infoUrl,
                        ),
                    // The source record, sent once — no longer also as `raw`.
                    upstream = raw,
                    status = campground.status,
                    statusDescription = campground.statusDescription,
                    kind = campground.kind,
                    parentName = campground.parentName?.takeIf { !it.trim().equals(campground.name.trim(), ignoreCase = true) },
                    amenities = AmenityDto.fromAll(campground.amenities),
                    cellCoverage = campground.cellService.map(CarrierSignalDto::from),
                    activities = campground.metadata?.activities.orEmpty(),
                    rating = campground.metadata?.rating?.let(RatingDto::from),
                    price = campground.price?.let(PriceDto::from),
                    schedule = campground.defaultCampsiteSchedule?.let(ScheduleDto::from),
                    maxRvLength = campground.maxRvLength,
                    maxTrailerLength = campground.maxTrailerLength,
                    hasPullThroughSites = campground.hasPullThroughSites,
                    bigRigFriendly = campground.bigRigFriendly,
                    links = CatalogColumnJson.elements(campground.links),
                    alerts = campground.alerts.map(AlertDto::from),
                    connections = campground.connections,
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
