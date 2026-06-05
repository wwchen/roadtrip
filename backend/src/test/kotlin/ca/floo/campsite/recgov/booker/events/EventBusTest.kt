package ca.floo.campsite.recgov.booker.events

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventBusTest {
    @Test
    fun `ids are monotonic across publishes`() {
        val bus = EventBus(replay = 16)
        val a = bus.publish(CampsiteEvent.MatchFound(matchJson = """{"id":1}"""))
        val b = bus.publish(CampsiteEvent.MatchFound(matchJson = """{"id":2}"""))
        val c = bus.publish(CampsiteEvent.Claimed(matchId = 1, companionId = "A", leaseExpires = "x"))
        assertTrue(b.id > a.id)
        assertTrue(c.id > b.id)
        assertEquals("match", a.wire?.type)
        assertEquals("claimed", c.wire?.type)
    }

    @Test
    fun `replay buffer holds last N wire envelopes for resume`() {
        val bus = EventBus(replay = 4)
        // LivenessTick is the canonical wire-only event; pump 10 of them.
        repeat(10) { bus.publish(CampsiteEvent.LivenessTick) }
        val replay = bus.replayBuffer()
        assertEquals(4, replay.size)
        // Latest 4 ids retained
        val ids = replay.map { it.id }
        assertEquals(listOf(7L, 8L, 9L, 10L), ids)
    }

    @Test
    fun `wire-eligible events project an Envelope and seed replay`() {
        val bus = EventBus(replay = 16)
        val typed = bus.publish(CampsiteEvent.Claimed(matchId = 42, companionId = "cmp-A", leaseExpires = "2026-06-04T22:00:00Z"))
        assertNotNull(typed.wire)
        assertEquals("claimed", typed.wire!!.type)
        assertTrue(typed.wire.data.contains("\"id\":42"))
        assertTrue(typed.wire.data.contains("\"companionId\":\"cmp-A\""))
        assertEquals(typed.id, typed.wire.id)
        assertEquals(listOf(typed.wire), bus.replayBuffer())
    }

    @Test
    fun `internal-only events do not project to wire`() {
        val bus = EventBus(replay = 16)
        val typed = bus.publish(CampsiteEvent.PollDue(alertId = 7))
        assertNull(typed.wire)
        assertEquals(emptyList(), bus.replayBuffer())
    }

    @Test
    fun `typed replay carries scheduler-only events alongside wire-eligible ones`() {
        val bus = EventBus(replay = 16)
        bus.publish(CampsiteEvent.PollDue(alertId = 1))
        bus.publish(CampsiteEvent.UserPolledNow(alertId = null))
        bus.publish(CampsiteEvent.MatchFound(matchJson = """{"id":1}"""))

        val typedReplay = bus.typedEvents.replayCache
        assertEquals(3, typedReplay.size)
        assertTrue(typedReplay[0].event is CampsiteEvent.PollDue)
        assertTrue(typedReplay[1].event is CampsiteEvent.UserPolledNow)
        assertTrue(typedReplay[2].event is CampsiteEvent.MatchFound)
        assertNull(typedReplay[0].wire)
        assertNull(typedReplay[1].wire)
        assertNotNull(typedReplay[2].wire)

        // Wire replay only carries the MatchFound
        assertEquals(1, bus.replayBuffer().size)
        assertEquals("match", bus.replayBuffer()[0].type)
    }
}
