package ca.floo.roadtrip.service.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecretCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val cipher = SecretCipher(key)

    @Test
    fun `seal then open round-trips`() {
        val sealed = cipher.seal("xoxb-super-secret")
        assertEquals("xoxb-super-secret", cipher.open(sealed))
    }

    @Test
    fun `each seal uses a fresh nonce`() {
        assertFalse(cipher.seal("same").contentEquals(cipher.seal("same")))
    }

    @Test
    fun `open rejects tampered ciphertext`() {
        val sealed = cipher.seal("x").also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertFailsWith<Exception> { cipher.open(sealed) }
    }
}
