package ca.floo.campsite.recgov.booker.events

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventBusTest {
    @Test
    fun `ids are monotonic across publishes`() {
        val bus = EventBus(replay = 16)
        val a = bus.publish("match", """{"id":1}""")
        val b = bus.publish("match", """{"id":2}""")
        val c = bus.publish("claimed", """{"id":1,"by":"A"}""")
        assertTrue(b.id > a.id)
        assertTrue(c.id > b.id)
        assertEquals("match", a.type)
        assertEquals("claimed", c.type)
    }

    @Test
    fun `replay buffer holds last N envelopes for resume`() {
        val bus = EventBus(replay = 4)
        repeat(10) { bus.publish("tick", """{"i":$it}""") }
        val replay = bus.replayBuffer()
        assertEquals(4, replay.size)
        // Latest 4 ids retained
        val ids = replay.map { it.id }
        assertEquals(listOf(7L, 8L, 9L, 10L), ids)
    }

    @Test
    fun `typed publish projects wire envelope when sseType is set`() {
        val bus = EventBus(replay = 16)
        val typed = bus.publish(CampsiteEvent.Claimed(matchId = 42, companionId = "cmp-A", leaseExpires = "2026-06-04T22:00:00Z"))
        assertNotNull(typed.wire)
        assertEquals("claimed", typed.wire!!.type)
        assertTrue(typed.wire.data.contains("\"id\":42"))
        assertTrue(typed.wire.data.contains("\"companionId\":\"cmp-A\""))
        assertEquals(typed.id, typed.wire.id)
        // Also visible in the replay buffer for SSE resume
        assertEquals(listOf(typed.wire), bus.replayBuffer())
    }

    @Test
    fun `internal-only events do not project to wire`() {
        val bus = EventBus(replay = 16)
        val typed = bus.publish(CampsiteEvent.PollDue(alertId = 7))
        assertNull(typed.wire)
        // Wire replay buffer is empty
        assertEquals(emptyList(), bus.replayBuffer())
    }

    @Test
    fun `legacy string publish surfaces as Legacy event on typed flow`() =
        runBlocking {
            val bus = EventBus(replay = 16)
            // Subscribe to the typed flow first by collecting from replay after publish
            bus.publish("match", """{"id":99}""")
            val first: TypedEnvelope = bus.typedEvents.first()
            val ev = first.event
            assertTrue(ev is CampsiteEvent.Legacy, "expected Legacy, got $ev")
            assertEquals("match", (ev as CampsiteEvent.Legacy).type)
            assertNotNull(first.wire)
            assertEquals("match", first.wire!!.type)
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
