package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.config.CampgroundSearchConfig
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsResponseDto
import ca.floo.roadtrip.model.api.campground.CampgroundFilterDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchResponseDto
import ca.floo.roadtrip.model.api.campground.CampgroundSummaryDto
import ca.floo.roadtrip.model.api.poi.AmenityDto
import ca.floo.roadtrip.model.api.poi.RatingDto
import ca.floo.roadtrip.model.domain.CampgroundSearchFilter
import ca.floo.roadtrip.model.domain.CampgroundSummaryRow
import ca.floo.roadtrip.model.domain.InvalidBoundaryException
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampgroundSearchRepo
import ca.floo.roadtrip.service.availability.BookingHorizonResolver
import ca.floo.roadtrip.service.availability.BookingIdentityResolver
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A request the caller can fix; [code] is the wire error code. */
class CampgroundSearchRequestException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

private val boundaryTypes = setOf("Polygon", "MultiPolygon")
private const val MIN_GROUP_SIZE = 1

internal class CampgroundSearchService(
    private val searchRepo: CampgroundSearchRepo,
    private val campgroundRepo: CampgroundRepo,
    private val bookingHorizons: BookingHorizonResolver,
    private val identities: BookingIdentityResolver,
    private val cta: CampgroundCta,
    private val config: CampgroundSearchConfig,
) {
    fun search(request: CampgroundSearchRequestDto): CampgroundSearchResponseDto {
        val boundary = validatedBoundary(request.boundary)
        val filter = validatedFilter(request.filter ?: CampgroundFilterDto())
        val result =
            try {
                searchRepo.searchWithinBoundary(boundary.toString(), filter, limit = config.maxResults)
            } catch (e: InvalidBoundaryException) {
                throw CampgroundSearchRequestException("bad_boundary", "boundary is not valid GeoJSON geometry")
            }
        return CampgroundSearchResponseDto(
            campgroundIds = result.poiIds,
            totalInBoundary = result.totalInBoundary,
            totalMatching = result.totalMatching,
            truncated = result.truncated,
        )
    }

    fun details(request: CampgroundDetailsRequestDto): CampgroundDetailsResponseDto {
        val ids = request.campgroundIds.distinct()
        if (ids.size > config.maxDetailIds) {
            throw CampgroundSearchRequestException(
                "too_many_ids",
                "at most ${config.maxDetailIds} campground_ids per request",
            )
        }
        if (ids.isEmpty()) return CampgroundDetailsResponseDto(campgrounds = emptyList())
        return CampgroundDetailsResponseDto(campgrounds = campgroundRepo.findSummariesByPoiIds(ids).map(::summaryOf))
    }

    private fun validatedBoundary(boundary: JsonObject?): JsonObject {
        boundary ?: throw CampgroundSearchRequestException("bad_boundary", "boundary is required")
        val type = (boundary["type"] as? JsonPrimitive)?.content
        if (type !in boundaryTypes) {
            throw CampgroundSearchRequestException("bad_boundary", "boundary must be a GeoJSON Polygon or MultiPolygon")
        }
        val coords = boundary["coordinates"]
        if (coords == null || coords is JsonNull) {
            throw CampgroundSearchRequestException("bad_boundary", "boundary has no coordinates")
        }
        return boundary
    }

    private fun validatedFilter(filter: CampgroundFilterDto): CampgroundSearchFilter {
        filter.groupSize?.let {
            if (it < MIN_GROUP_SIZE) {
                throw CampgroundSearchRequestException("bad_request", "group_size must be >= $MIN_GROUP_SIZE")
            }
        }
        return CampgroundSearchFilter(siteType = filter.siteType, groupSize = filter.groupSize, amenities = filter.amenities)
    }

    /** The same decision `CampgroundService` makes for `availability_supported` and `booking_system`. */
    private fun summaryOf(row: CampgroundSummaryRow): CampgroundSummaryDto {
        val campground = row.campground
        val bookingRef = identities.forCampground(campground, bookingHorizons.servingProvider(campground))
        return CampgroundSummaryDto(
            id = row.poiId,
            campgroundId = campground.id,
            name = campground.name,
            region = campground.location?.region,
            agency = campground.management?.agency,
            lng = row.lng,
            lat = row.lat,
            rating = campground.metadata?.rating?.let(RatingDto::from),
            availabilitySupported = bookingRef != null,
            bookingSystem = cta.bookingSystem(bookingRef),
            amenities = AmenityDto.fromAll(campground.amenities),
            siteCounts = row.summary?.siteCounts ?: emptyMap(),
            siteTotal = row.summary?.siteTotal ?: 0,
            maxPeople = row.summary?.maxPeople,
        )
    }
}
