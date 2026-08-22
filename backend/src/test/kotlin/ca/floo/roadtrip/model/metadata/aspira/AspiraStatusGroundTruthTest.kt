package ca.floo.roadtrip.model.metadata.aspira

import ca.floo.roadtrip.client.aspira.HttpAspiraAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private const val ALICE_LAKE_MAP_ID = -2147483647
private val captureStart: LocalDate = LocalDate.parse("2026-08-22")

private val groundTruthEnd: LocalDate = LocalDate.parse("2026-09-04")

private val bookableDay: LocalDate = LocalDate.parse("2026-08-31")

/**
 * Pins the code mapping to the vendor's own rendered calendar rather than to
 * our reading of the codes: sites 38 and 39 showed bookable on 2026-08-31 only.
 * Inverting [AspiraStatus] flips these assertions.
 */
class AspiraStatusGroundTruthTest {
    private val parsed =
        HttpAspiraAvailabilityClient().parse(
            File(
                javaClass.classLoader
                    .getResource("etl-fixtures/aspira-availability/bcparks-alice-lake-map.json")!!
                    .toURI(),
            ).readText(),
            ALICE_LAKE_MAP_ID,
        )

    @Test
    fun `sites 38 and 39 are bookable only on the day the vendor calendar shows them open`() {
        for ((resourceId, codes) in parsed.byResource) {
            var day = captureStart
            var index = 0
            while (!day.isAfter(groundTruthEnd)) {
                val status = AspiraStatus.classify(codes[index])
                if (day == bookableDay) {
                    assertEquals(AvailabilityStatus.AVAILABLE, status, "site $resourceId on $day")
                } else {
                    assertNotEquals(AvailabilityStatus.AVAILABLE, status, "site $resourceId on $day")
                }
                day = day.plusDays(1)
                index++
            }
        }
    }

    @Test
    fun `the park rollup agrees with the sites on the bookable day`() {
        val index = ChronoUnit.DAYS.between(captureStart, bookableDay).toInt()
        assertEquals(AvailabilityStatus.AVAILABLE, AspiraStatus.classify(parsed.parkRollup[index]))
    }
}
