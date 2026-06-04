package ca.floo.campsite.recgov.booker.events

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
}
