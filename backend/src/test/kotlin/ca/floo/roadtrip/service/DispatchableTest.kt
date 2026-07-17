package ca.floo.roadtrip.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DispatchableTest {
    private data class FakeHandler(
        val key: String,
    ) : Dispatchable<String> {
        override fun canHandle(key: String): Boolean = key == this.key
    }

    private val handlers = listOf(FakeHandler("a"), FakeHandler("b"), FakeHandler("c"))

    @Test
    fun `firstHandlerFor returns first match`() {
        assertEquals(FakeHandler("b"), handlers.firstHandlerFor("b"))
    }

    @Test
    fun `firstHandlerFor returns null when no match`() {
        assertNull(handlers.firstHandlerFor("z"))
    }

    @Test
    fun `allHandlersFor returns all matches`() {
        val multi = handlers + FakeHandler("b")
        assertEquals(2, multi.allHandlersFor("b").size)
    }

    @Test
    fun `allHandlersFor returns empty for no match`() {
        assertEquals(emptyList(), handlers.allHandlersFor("z"))
    }
}
