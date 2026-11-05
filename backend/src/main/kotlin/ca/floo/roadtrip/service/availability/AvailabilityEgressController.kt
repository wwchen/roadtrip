package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityEgressCellDto
import ca.floo.roadtrip.model.api.AvailabilityEgressRequestDto
import ca.floo.roadtrip.model.api.AvailabilityEgressResponseDto
import ca.floo.roadtrip.model.api.ProviderRefDto
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal class AvailabilityEgressController(
    private val campgroundRepo: CampgroundRepo,
    private val campsitesRepo: CampsiteRepo,
    private val availabilityService: CampsiteAvailabilityService,
    private val dateResolver: AvailabilityDateResolver,
) {
    suspend fun fetch(request: AvailabilityEgressRequestDto): AvailabilityEgressResponseDto {
        val campground =
            campgroundRepo.findByProviderRef(request.campground.dataProvider, request.campground.dataProviderRef)
                ?: throw AvailabilityServiceError.NotFound
        val startDate = parseDate(request.startDate)
        val endDate = parseDate(request.endDate)
        if (availabilityService.bookingHorizonDaysFor(campground) == null) throw AvailabilityServiceError.UnknownCampground
        val campsites = campsitesRepo.findByCampground(campground.id)
        if (campsites.isEmpty()) return AvailabilityEgressResponseDto(request.startDate, request.endDate, emptyList())

        val result =
            availabilityService.fetchAvailability(
                campground = campground,
                campsites = campsites,
                campgroundCampsites = campsites,
                startDate = startDate,
                endDate = endDate,
                dateContext = dateResolver.context(campground.location?.latitude, campground.location?.longitude),
            )
        val refById = campsites.associate { it.id to ProviderRefDto(it.dataProvider, it.dataProviderRefValue) }
        return AvailabilityEgressResponseDto(
            startDate = result.startDate.toString(),
            endDate = result.endDate.toString(),
            cells =
                result.batch.observations.mapNotNull { observation ->
                    observation.campsiteId?.let(refById::get)?.let { campsite ->
                        AvailabilityEgressCellDto(
                            campsite = campsite,
                            date = observation.date.toString(),
                            status = observation.status,
                            observedAt = observation.observedAt.toString(),
                        )
                    }
                },
        )
    }

    private fun parseDate(raw: String): LocalDate =
        try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw AvailabilityServiceError.BadDateWindow.Invalid
        }
}
