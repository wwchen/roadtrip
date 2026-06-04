package ca.floo.campsite.recgov.booker.events

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionRegistryTest {
    @Test
    fun `first heartbeat does not flag came-back`() {
        val r = CompanionRegistry(Duration.ofSeconds(90))
        assertFalse(r.heartbeat("A"))
    }

    @Test
    fun `silence past threshold makes companion offline once`() {
        val r = CompanionRegistry(Duration.ofSeconds(30))
        val t0 = Instant.parse("2026-06-04T12:00:00Z")
        r.heartbeat("A", t0)
        // Below threshold — no transition.
        assertTrue(r.sweepOffline(t0.plusSeconds(20)).isEmpty())
        // Past threshold — fresh transition.
        val sweep1 = r.sweepOffline(t0.plusSeconds(45))
        assertEquals(1, sweep1.size)
        assertEquals("A", sweep1[0].id)
        // Already-offline entries are idempotent.
        assertTrue(r.sweepOffline(t0.plusSeconds(60)).isEmpty())
    }

    @Test
    fun `heartbeat after offline returns came-back true exactly once`() {
        val r = CompanionRegistry(Duration.ofSeconds(30))
        val t0 = Instant.parse("2026-06-04T12:00:00Z")
        r.heartbeat("A", t0)
        r.sweepOffline(t0.plusSeconds(45))
        assertTrue(r.heartbeat("A", t0.plusSeconds(50)), "first hb after offline → true")
        assertFalse(r.heartbeat("A", t0.plusSeconds(60)), "second hb stays online → false")
    }
}
