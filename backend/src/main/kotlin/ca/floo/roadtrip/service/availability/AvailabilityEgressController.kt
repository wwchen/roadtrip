package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityEgressCellDto
import ca.floo.roadtrip.model.api.AvailabilityEgressMode
import ca.floo.roadtrip.model.api.AvailabilityEgressRequestDto
import ca.floo.roadtrip.model.api.AvailabilityEgressResponseDto
import ca.floo.roadtrip.model.api.ProviderRefDto
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal class AvailabilityEgressController(
    private val campgroundRepo: CampgroundRepo,
    private val campsitesRepo: CampsiteRepo,
    private val availabilityService: CampsiteAvailabilityService,
    private val dateResolver: AvailabilityDateResolver,
    private val availabilityRepo: AvailabilityRepo,
) {
    suspend fun fetch(request: AvailabilityEgressRequestDto): AvailabilityEgressResponseDto {
        val campground =
            campgroundRepo.findByProviderRef(request.campground.dataProvider, request.campground.dataProviderRef)
                ?: throw AvailabilityServiceError.NotFound
        val startDate = request.startDate?.let(::parseDate)
        val endDate = request.endDate?.let(::parseDate)
        val freshAtOrAfter = request.freshAtOrAfter?.let(::parseInstant)
        val context = dateResolver.context(campground.location?.latitude, campground.location?.longitude)
        val (window, latestDate) = availabilityService.resolveTargetWindow(campground, startDate, endDate, context)
        val campsites = campsitesRepo.findByCampground(campground.id)
        val refById = campsites.associate { it.id to ProviderRefDto(it.dataProvider, it.dataProviderRefValue) }
        val (observations, fromCache) =
            when {
                campsites.isEmpty() -> emptyList<CampsiteDayObservation>() to true
                request.mode == AvailabilityEgressMode.STORED -> stored(campsites, window) to true
                else ->
                    availabilityService
                        .fetchAvailability(
                            campground = campground,
                            campsites = campsites,
                            campgroundCampsites = campsites,
                            startDate = window.startDate,
                            endDate = window.endDate,
                            dateContext = context,
                            freshAtOrAfter = freshAtOrAfter,
                        ).batch
                        .let { it.observations to it.cacheBlock.hit }
            }
        return AvailabilityEgressResponseDto(
            startDate = window.startDate.toString(),
            endDate = window.endDate.toString(),
            latestDate = latestDate?.toString(),
            campsites = refById.values.toList(),
            cells =
                observations.mapNotNull { observation ->
                    observation.campsiteId?.let(refById::get)?.let { campsite ->
                        AvailabilityEgressCellDto(
                            campsite = campsite,
                            date = observation.date.toString(),
                            status = observation.status,
                            observedAt = observation.observedAt.toString(),
                        )
                    }
                },
            fromCache = fromCache,
        )
    }

    private fun stored(
        campsites: List<Campsite>,
        window: ResolvedDateWindow,
    ): List<CampsiteDayObservation> {
        val dates = (0 until ChronoUnit.DAYS.between(window.startDate, window.endDate)).map { window.startDate.plusDays(it) }
        return availabilityRepo.readCurrent(campsites.map { it.id }, dates).map { cell ->
            CampsiteDayObservation(
                campsiteId = cell.campsiteId,
                date = cell.targetDate,
                observedAt = cell.observedAt.toInstant(),
                status = cell.status,
            )
        }
    }

    private fun parseInstant(raw: String): Instant =
        try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw AvailabilityServiceError.BadDateWindow.Invalid
        }

    private fun parseDate(raw: String): LocalDate =
        try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw AvailabilityServiceError.BadDateWindow.Invalid
        }
}
