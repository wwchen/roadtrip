package ca.floo.roadtrip.service.planning

import ca.floo.roadtrip.model.api.PlanningBudgetDto
import ca.floo.roadtrip.model.api.PlanningDriveDto
import ca.floo.roadtrip.model.api.PlanningStayDto
import ca.floo.roadtrip.model.api.PlanningTemplateCardDto
import ca.floo.roadtrip.model.api.PlanningTemplatesDto
import ca.floo.roadtrip.model.api.PlanningTimelineDayDto
import ca.floo.roadtrip.model.api.PlanningTimelineDto
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.metadata.registry.TripStay
import ca.floo.roadtrip.model.metadata.registry.TripTemplate
import ca.floo.roadtrip.model.metadata.registry.TripTemplateRegistry
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

// Narrow catalog seam so the service (and its tests) doesn't need a live
// database: production wires this to CampgroundRepo::findByDataProviderRef.
fun interface PlanningCampgroundLookup {
    fun find(
        provider: String,
        ref: String,
    ): Campground?
}

private const val STAY_KIND_CATALOG = "catalog"
private const val STAY_KIND_MANUAL = "manual"
private const val BOOKING_STATE_BOOKABLE = "bookable"
private const val BOOKING_STATE_CALL = "call"
private const val BOOKING_STATE_UNLINKED = "unlinked"

/**
 * Planning Mode M1 (RFC 0011): serve the authored template shelf and
 * instantiate a template against a start date. Stateless — trips are not
 * persisted yet; the only live inputs are the catalog (campground ref
 * resolution) and the clock (lead-time warnings).
 */
class PlanningService(
    private val tripTemplateRegistry: TripTemplateRegistry,
    private val campgroundLookup: PlanningCampgroundLookup,
) {
    fun listTemplates(): PlanningTemplatesDto = PlanningTemplatesDto(templates = tripTemplateRegistry.templates.map(::toCard))

    fun timeline(
        templateId: String,
        start: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): PlanningTimelineDto? {
        val template = tripTemplateRegistry.findById(templateId) ?: return null
        val days =
            template.itinerary.map { day ->
                val date = start.plusDays((day.day - 1).toLong())
                PlanningTimelineDayDto(
                    day = day.day,
                    date = date.toString(),
                    title = day.title,
                    evStatus = day.evStatus,
                    drive =
                        day.drive?.let {
                            PlanningDriveDto(
                                from = it.from,
                                to = it.to,
                                miles = it.miles,
                                minutes = it.minutes,
                                superchargers = it.superchargers,
                            )
                        },
                    stay = day.stay?.let(::toStayDto),
                    highlights = day.highlights,
                    sidequests = day.sidequests,
                )
            }
        return PlanningTimelineDto(
            templateId = template.id,
            name = template.name,
            startDate = start.toString(),
            endDate = start.plusDays((template.days - 1).toLong()).toString(),
            warnings = warningsFor(template, start, today),
            days = days,
        )
    }

    private fun toStayDto(stay: TripStay): PlanningStayDto {
        val manual = stay.manual
        if (manual != null) {
            return PlanningStayDto(
                name = stay.name,
                kind = STAY_KIND_MANUAL,
                bookingState = BOOKING_STATE_CALL,
                phone = manual.phone,
                url = manual.url,
                resolved = false,
            )
        }
        val ref = stay.campground
        val campground = ref?.let { campgroundLookup.find(it.provider, it.ref) }
        return PlanningStayDto(
            name = stay.name,
            kind = STAY_KIND_CATALOG,
            bookingState =
                if (campground?.bookingProvider != null) BOOKING_STATE_BOOKABLE else BOOKING_STATE_UNLINKED,
            campgroundId = campground?.id,
            bookingProvider = campground?.bookingProvider ?: ref?.provider,
            url = campground?.reservationUrl,
            resolved = campground != null,
        )
    }

    private fun warningsFor(
        template: TripTemplate,
        start: LocalDate,
        today: LocalDate,
    ): List<String> {
        val warnings = mutableListOf<String>()
        val primeMonths = template.season.primeMonths
        if (primeMonths.isNotEmpty() && start.monthValue !in primeMonths) {
            val names =
                primeMonths.joinToString(", ") {
                    Month.of(it).getDisplayName(TextStyle.SHORT, Locale.US)
                }
            warnings +=
                "${start.month.getDisplayName(TextStyle.FULL, Locale.US)} is outside this trip's prime months ($names)."
        }
        val leadTimeDays = template.booking.leadTimeDays
        if (leadTimeDays != null && !start.isAfter(today.plusDays(leadTimeDays.toLong()))) {
            warnings +=
                "Start date is inside the typical $leadTimeDays-day booking window for the scarce campgrounds — " +
                "book or watch them immediately."
        }
        return warnings
    }

    private fun toCard(template: TripTemplate): PlanningTemplateCardDto =
        PlanningTemplateCardDto(
            id = template.id,
            name = template.name,
            tagline = template.tagline,
            origin = template.origin,
            terminus = template.terminus,
            days = template.days,
            totalMiles = template.totalMiles,
            avgDriveMinutesPerDay = template.avgDriveMinutesPerDay,
            longestDriveMinutes = template.longestDriveMinutes,
            seasonPrimeMonths = template.season.primeMonths,
            seasonNotes = template.season.notes,
            evGrade = template.ev.grade,
            evNotes = template.ev.notes,
            maxSuperchargerGapMi = template.ev.maxSuperchargerGapMi,
            hookupCriticalDays = template.ev.hookupCriticalDays,
            bookingGrade = template.booking.grade,
            bookingLeadTimeDays = template.booking.leadTimeDays,
            bookingNotes = template.booking.notes,
            budget =
                PlanningBudgetDto(
                    campFeesUsd = template.budgetUsd.campFees,
                    chargingUsd = template.budgetUsd.charging,
                    entryFeesUsd = template.budgetUsd.entryFees,
                    totalUsd = template.budgetUsd.campFees + template.budgetUsd.charging + template.budgetUsd.entryFees,
                    notes = template.budgetUsd.notes,
                ),
        )
}
