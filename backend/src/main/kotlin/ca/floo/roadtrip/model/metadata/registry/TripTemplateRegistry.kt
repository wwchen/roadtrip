package ca.floo.roadtrip.model.metadata.registry

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets

// Authored trip templates for Planning Mode (RFC 0011, M1).
//
// Templates are content, not user data: route legs, distances, charger stops,
// season windows, and grades are authored once in trip-templates.yaml. The
// app computes only dates (instantiation against a start date) and booking
// state (resolution against the canonical catalog). Loaded once at boot,
// validated the same way PoiRegistry is: fail fast on typos rather than at
// first request.
@Serializable
class TripTemplateRegistry(
    val templates: List<TripTemplate>,
) {
    fun findById(id: String): TripTemplate? = templates.firstOrNull { it.id == id }

    fun validate(sourceName: String = "trip templates") {
        val errs = mutableListOf<String>()
        val ids = mutableSetOf<String>()
        for (template in templates) {
            if (!ids.add(template.id)) errs += "duplicate template id='${template.id}'"
            template.validateInto(errs)
        }
        require(errs.isEmpty()) { "$sourceName invalid:\n - ${errs.joinToString("\n - ")}" }
    }

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

        fun loadResource(
            resourceName: String,
            classLoader: ClassLoader =
                Thread.currentThread().contextClassLoader
                    ?: TripTemplateRegistry::class.java.classLoader,
        ): TripTemplateRegistry {
            val normalized = resourceName.trim().removePrefix("/")
            require(normalized.isNotEmpty()) { "trip template resource name must not be blank" }
            val content =
                classLoader
                    .getResourceAsStream(normalized)
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    ?: error("trip template resource '$normalized' not found on classpath")
            return loadString(content = content, sourceName = "classpath:$normalized")
        }

        fun loadString(
            content: String,
            sourceName: String = "trip templates",
        ): TripTemplateRegistry {
            val registry = yaml.decodeFromString(serializer(), content)
            registry.validate(sourceName)
            return registry
        }
    }
}

private val validGrades = setOf("green", "yellow", "red")
private const val MIN_MONTH = 1
private const val MAX_MONTH = 12

@Serializable
class TripTemplate(
    val id: String,
    val name: String,
    val tagline: String,
    val origin: String,
    val terminus: String,
    val days: Int,
    @SerialName("total_miles")
    val totalMiles: Int,
    @SerialName("avg_drive_minutes_per_day")
    val avgDriveMinutesPerDay: Int,
    @SerialName("longest_drive_minutes")
    val longestDriveMinutes: Int,
    val season: TripSeason,
    val ev: TripEvProfile,
    val booking: TripBookingProfile,
    @SerialName("budget_usd")
    val budgetUsd: TripBudget,
    val itinerary: List<TripTemplateDay>,
) {
    internal fun validateInto(errs: MutableList<String>) {
        val label = "template '$id'"
        if (itinerary.size != days) {
            errs += "$label declares days=$days but has ${itinerary.size} itinerary entries"
        }
        itinerary.forEachIndexed { index, day ->
            if (day.day != index + 1) {
                errs += "$label itinerary entry ${index + 1} has day=${day.day} (must be sequential from 1)"
            }
            if (day.evStatus !in validGrades) {
                errs += "$label day ${day.day} has ev_status='${day.evStatus}' (must be one of $validGrades)"
            }
            val stay = day.stay
            if (stay != null && (stay.campground == null) == (stay.manual == null)) {
                errs += "$label day ${day.day} stay must declare exactly one of campground | manual"
            }
        }
        if (ev.grade !in validGrades) errs += "$label ev.grade='${ev.grade}' invalid"
        if (booking.grade !in validGrades) errs += "$label booking.grade='${booking.grade}' invalid"
        for (month in season.primeMonths) {
            if (month !in MIN_MONTH..MAX_MONTH) errs += "$label season prime month $month out of range"
        }
        for (day in ev.hookupCriticalDays) {
            if (day !in 1..days) errs += "$label ev hookup_critical_day $day out of itinerary range"
        }
    }
}

@Serializable
class TripSeason(
    @SerialName("prime_months")
    val primeMonths: List<Int> = emptyList(),
    val notes: String? = null,
)

@Serializable
class TripEvProfile(
    val grade: String,
    @SerialName("max_supercharger_gap_mi")
    val maxSuperchargerGapMi: Int? = null,
    @SerialName("hookup_critical_days")
    val hookupCriticalDays: List<Int> = emptyList(),
    val notes: String? = null,
)

@Serializable
class TripBookingProfile(
    val grade: String,
    @SerialName("lead_time_days")
    val leadTimeDays: Int? = null,
    val notes: String? = null,
)

@Serializable
class TripBudget(
    @SerialName("camp_fees")
    val campFees: Int,
    val charging: Int,
    @SerialName("entry_fees")
    val entryFees: Int,
    val notes: String? = null,
)

@Serializable
class TripTemplateDay(
    val day: Int,
    val title: String,
    @SerialName("ev_status")
    val evStatus: String,
    val drive: TripDrive? = null,
    val stay: TripStay? = null,
    val highlights: List<String> = emptyList(),
    val sidequests: List<String> = emptyList(),
)

@Serializable
class TripDrive(
    val from: String,
    val to: String,
    val miles: Int,
    val minutes: Int,
    val superchargers: List<String> = emptyList(),
)

@Serializable
class TripStay(
    val name: String,
    val campground: TripCampgroundRef? = null,
    val manual: TripManualStay? = null,
)

@Serializable
class TripCampgroundRef(
    val provider: String,
    val ref: String,
)

@Serializable
class TripManualStay(
    val name: String,
    val phone: String? = null,
    val url: String? = null,
)
