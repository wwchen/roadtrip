package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityResponseDto
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import java.time.LocalDate

internal sealed interface CampgroundWindow {
    val poiId: Long
    val campground: Campground
    val window: ResolvedDateWindow
    val earliestDate: LocalDate
    val latestDate: LocalDate?
    val campgroundCampsites: List<Campsite>

    data class Answered(
        override val poiId: Long,
        override val campground: Campground,
        override val window: ResolvedDateWindow,
        override val earliestDate: LocalDate,
        override val latestDate: LocalDate?,
        override val campgroundCampsites: List<Campsite>,
        val campsites: List<Campsite>,
        val streams: List<CampsiteStream>,
        val provider: BookingProvider,
        val campgroundRef: BookingProviderRef?,
        val cache: AvailabilityCacheBlock,
        val pollingSupported: Boolean,
        val reopensOnByCampsite: Map<Long, LocalDate>,
    ) : CampgroundWindow

    data class NoCampsitesMatched(
        override val poiId: Long,
        override val campground: Campground,
        override val window: ResolvedDateWindow,
        override val earliestDate: LocalDate,
        override val latestDate: LocalDate?,
        override val campgroundCampsites: List<Campsite>,
    ) : CampgroundWindow
}

internal data class CampsiteEnvelope(
    val response: AvailabilityResponseDto,
    val stream: CampsiteStream,
)
