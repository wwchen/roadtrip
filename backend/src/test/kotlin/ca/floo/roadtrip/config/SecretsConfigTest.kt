package ca.floo.roadtrip.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SecretsConfigTest {
    @Test
    fun `same bytes are equal and share hashCode`() {
        val bytes = ByteArray(32) { it.toByte() }
        val a = SecretsConfig(bytes.copyOf())
        val b = SecretsConfig(bytes.copyOf())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different bytes are not equal`() {
        val a = SecretsConfig(ByteArray(32) { 0 })
        val b = SecretsConfig(ByteArray(32) { 1 })
        assertNotEquals(a, b)
    }
}
