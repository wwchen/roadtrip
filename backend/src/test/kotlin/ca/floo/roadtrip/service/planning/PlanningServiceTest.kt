package ca.floo.roadtrip.service.planning

import ca.floo.roadtrip.model.metadata.registry.TripTemplateRegistry
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEMPLATE_YAML = """
templates:
  - id: two-day-test
    name: "Two Day Test"
    tagline: "A short authored trip"
    origin: "Las Vegas, NV"
    terminus: "Springdale, UT"
    days: 2
    total_miles: 200
    avg_drive_minutes_per_day: 90
    longest_drive_minutes: 170
    season:
      prime_months: [9, 10]
      notes: "Fall is prime"
    ev:
      grade: yellow
      max_supercharger_gap_mi: 150
      hookup_critical_days: [1]
      notes: "One sparse stretch"
    booking:
      grade: red
      lead_time_days: 150
    budget_usd:
      camp_fees: 60
      charging: 25
      entry_fees: 35
    itinerary:
      - day: 1
        title: "Vegas to Zion"
        ev_status: green
        drive:
          from: "Las Vegas, NV"
          to: "Springdale, UT"
          miles: 160
          minutes: 170
          superchargers: ["St. George, UT"]
        stay:
          name: "Watchman Campground"
          campground: { provider: recgov, ref: "232445" }
        highlights: ["Canyon Overlook sunset"]
      - day: 2
        title: "Zion"
        ev_status: green
        stay:
          name: "Ruby's Inn RV Park"
          manual:
            name: "Ruby's Inn RV Park"
            phone: "+1 555-000-0000"
"""

class PlanningServiceTest {
    private val noCatalog = PlanningCampgroundLookup { _, _ -> null }

    private fun serviceWith(lookup: PlanningCampgroundLookup) =
        PlanningService(
            tripTemplateRegistry = TripTemplateRegistry.loadString(TEMPLATE_YAML),
            campgroundLookup = lookup,
        )

    @Test
    fun `listTemplates sums the budget and exposes card fields`() {
        val card = serviceWith(noCatalog).listTemplates().templates.single()
        assertEquals("two-day-test", card.id)
        assertEquals(60 + 25 + 35, card.budget.totalUsd)
        assertEquals(listOf(9, 10), card.seasonPrimeMonths)
        assertEquals(listOf(1), card.hookupCriticalDays)
    }

    @Test
    fun `timeline dates each day forward from the start date`() {
        val timeline =
            serviceWith(noCatalog).timeline(
                templateId = "two-day-test",
                start = LocalDate.of(2026, 9, 18),
                today = LocalDate.of(2026, 1, 1),
            )!!
        assertEquals("2026-09-18", timeline.startDate)
        assertEquals("2026-09-19", timeline.endDate)
        assertEquals(listOf("2026-09-18", "2026-09-19"), timeline.days.map { it.date })
        assertEquals(
            "St. George, UT",
            timeline.days[0]
                .drive!!
                .superchargers
                .single(),
        )
        assertNull(timeline.days[1].drive)
    }

    @Test
    fun `an unresolved catalog stay is unlinked and a manual stay is call`() {
        val timeline =
            serviceWith(noCatalog).timeline(
                templateId = "two-day-test",
                start = LocalDate.of(2026, 9, 18),
                today = LocalDate.of(2026, 1, 1),
            )!!
        val catalogStay = timeline.days[0].stay!!
        assertEquals("catalog", catalogStay.kind)
        assertEquals("unlinked", catalogStay.bookingState)
        assertEquals(false, catalogStay.resolved)

        val manualStay = timeline.days[1].stay!!
        assertEquals("manual", manualStay.kind)
        assertEquals("call", manualStay.bookingState)
        assertEquals("+1 555-000-0000", manualStay.phone)
    }

    @Test
    fun `an off-season start warns and a start inside the booking window warns`() {
        val timeline =
            serviceWith(noCatalog).timeline(
                templateId = "two-day-test",
                start = LocalDate.of(2026, 2, 1),
                today = LocalDate.of(2026, 1, 1),
            )!!
        assertEquals(2, timeline.warnings.size)
        assertTrue(timeline.warnings.any { it.contains("prime months") })
        assertTrue(timeline.warnings.any { it.contains("150-day booking window") })
    }

    @Test
    fun `a prime-season start far ahead of the booking window is warning-free`() {
        val timeline =
            serviceWith(noCatalog).timeline(
                templateId = "two-day-test",
                start = LocalDate.of(2026, 9, 18),
                today = LocalDate.of(2026, 1, 1),
            )!!
        assertEquals(emptyList(), timeline.warnings)
    }

    @Test
    fun `an unknown template id returns null`() {
        assertNull(
            serviceWith(noCatalog).timeline(templateId = "nope", start = LocalDate.of(2026, 9, 18)),
        )
    }

    @Test
    fun `the shipped trip-templates resource loads and validates`() {
        val registry = TripTemplateRegistry.loadResource("trip-templates.yaml")
        assertTrue(registry.templates.isNotEmpty())
    }
}
