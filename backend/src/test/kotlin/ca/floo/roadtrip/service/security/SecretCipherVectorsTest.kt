package ca.floo.roadtrip.service.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals

private const val VECTORS_RESOURCE = "/secret-cipher-vectors.json"

@Serializable
private data class CipherVector(
    val plaintext: String,
    val sealed: String,
)

@Serializable
private data class CipherVectors(
    val key: String,
    val vectors: List<CipherVector>,
)

class SecretCipherVectorsTest {
    @Test
    fun `opens the vectors apps-web seals and reads`() {
        val text = checkNotNull(javaClass.getResource(VECTORS_RESOURCE)).readText()
        val fixture = Json.decodeFromString<CipherVectors>(text)
        val cipher = SecretCipher(Base64.getDecoder().decode(fixture.key))

        fixture.vectors.forEach { vector ->
            assertEquals(vector.plaintext, cipher.open(Base64.getDecoder().decode(vector.sealed)))
        }
    }

    @Test
    fun `a value sealed here round trips through the same format`() {
        val fixture = Json.decodeFromString<CipherVectors>(checkNotNull(javaClass.getResource(VECTORS_RESOURCE)).readText())
        val cipher = SecretCipher(Base64.getDecoder().decode(fixture.key))

        assertEquals(fixture.vectors.size, fixture.vectors.count { cipher.open(cipher.seal(it.plaintext)) == it.plaintext })
    }
}
