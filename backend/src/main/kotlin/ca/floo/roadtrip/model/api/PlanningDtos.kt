package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class PlanningTemplatesDto(
    val templates: List<PlanningTemplateCardDto>,
)

@Serializable
data class PlanningTemplateCardDto(
    val id: String,
    val name: String,
    val tagline: String,
    val origin: String,
    val terminus: String,
    val days: Int,
    val totalMiles: Int,
    val avgDriveMinutesPerDay: Int,
    val longestDriveMinutes: Int,
    val seasonPrimeMonths: List<Int>,
    val seasonNotes: String?,
    val evGrade: String,
    val evNotes: String?,
    val maxSuperchargerGapMi: Int?,
    val hookupCriticalDays: List<Int>,
    val bookingGrade: String,
    val bookingLeadTimeDays: Int?,
    val bookingNotes: String?,
    val budget: PlanningBudgetDto,
)

@Serializable
data class PlanningBudgetDto(
    val campFeesUsd: Int,
    val chargingUsd: Int,
    val entryFeesUsd: Int,
    val totalUsd: Int,
    val notes: String?,
)

@Serializable
data class PlanningTimelineDto(
    val templateId: String,
    val name: String,
    val startDate: String,
    val endDate: String,
    val warnings: List<String>,
    val days: List<PlanningTimelineDayDto>,
)

@Serializable
data class PlanningTimelineDayDto(
    val day: Int,
    val date: String,
    val title: String,
    val evStatus: String,
    val drive: PlanningDriveDto? = null,
    val stay: PlanningStayDto? = null,
    val highlights: List<String>,
    val sidequests: List<String>,
)

@Serializable
data class PlanningDriveDto(
    val from: String,
    val to: String,
    val miles: Int,
    val minutes: Int,
    val superchargers: List<String>,
)

@Serializable
data class PlanningStayDto(
    val name: String,
    val kind: String,
    val bookingState: String,
    val campgroundId: Long? = null,
    val bookingProvider: String? = null,
    val phone: String? = null,
    val url: String? = null,
    val resolved: Boolean,
)

@Serializable
data class PlanningErrorDto(
    val error: String,
)
