package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.StayCheckConfig
import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.api.StayCheckOutcome
import ca.floo.roadtrip.model.api.StayOutcome
import ca.floo.roadtrip.route.common.wireNames
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CONFIG_MAX_POIS = 7
private const val CONFIG_FAN_OUT = 3
private const val CONFIG_IP_LIMIT = 700
private const val CONFIG_CEILING_SECONDS = 7_200L
private const val CONFIG_FLOOR_SECONDS = 90L

/** `PT45S`, `PT2M`: how `Duration.toString` writes a duration, which is not how a reader does. */
private val isoDuration = Regex("""\bPT\d""")

/**
 * The text `/api/docs` publishes for the stay check. It is built from the enums
 * and the config, so these pin that it says what they say rather than what it
 * said when it was written.
 */
class StayCheckDescriptionTest {
    private val config =
        StayCheckConfig(
            maxPois = CONFIG_MAX_POIS,
            fanOutConcurrency = CONFIG_FAN_OUT,
            defaultMaxAge = Duration.ofSeconds(CONFIG_CEILING_SECONDS),
            ipPoiLimitPerMinute = CONFIG_IP_LIMIT,
            minMaxAge = Duration.ofSeconds(CONFIG_FLOOR_SECONDS),
        )
    private val description = stayCheckDescription(config)

    @Test
    fun `it states every outcome and status the wire can carry`() {
        val vocabulary = wireNames<StayOutcome>() + wireNames<StayCheckOutcome>() + wireNames<StayAvailabilityStatus>()
        val missing = vocabulary.filterNot { "`$it`" in description }
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `the wire names it reads are the serial names, not the Kotlin names`() {
        assertEquals(listOf("answered", "refused", "timed_out", "failed"), wireNames<StayOutcome>())
        assertEquals(listOf("complete", "deadline_reached"), wireNames<StayCheckOutcome>())
    }

    @Test
    fun `it says max_age_seconds is clamped into the server's range, with that range`() {
        assertTrue("clamped" in description, description)
        assertTrue("$CONFIG_FLOOR_SECONDS seconds if lower" in description, description)
        assertTrue("$CONFIG_CEILING_SECONDS seconds if higher" in description, description)
        assertTrue("never a 400" in description, description)
    }

    @Test
    fun `it writes every duration as seconds, never as an ISO-8601 duration`() {
        for (bound in listOf(config.perPoiTimeout, config.streamTimeout, config.streamHeartbeat)) {
            assertTrue("${bound.seconds} seconds" in description, description)
        }
        assertTrue(isoDuration.find(description) == null, description)
    }

    @Test
    fun `it carries the config's limits and nothing of the removed scan`() {
        assertTrue("1..$CONFIG_MAX_POIS" in description, description)
        assertTrue("$CONFIG_IP_LIMIT a minute" in description, description)
        for (gone in listOf("min_nights", "longest_run_nights", "fresh", "the scan")) {
            assertTrue(gone !in description, "the description still mentions `$gone`")
        }
    }
}
