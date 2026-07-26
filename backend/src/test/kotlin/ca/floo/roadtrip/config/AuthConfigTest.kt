package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthConfigTest {
    private val complete =
        mapOf(
            "issuer" to "https://tenant.example.com",
            "client-id" to "client-abc",
            "client-secret" to "shh",
        )

    private fun section(values: Map<String, String>) = ConfigSection(values.mapKeys { "roadtrip.auth.${it.key}" }).section("roadtrip.auth")

    @Test
    fun `a complete section parses`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(complete)))

        assertEquals("https://tenant.example.com", config.issuer)
        assertEquals("client-abc", config.clientId)
        assertEquals("shh", config.clientSecret)
        assertEquals("oidc", config.provider)
        assertEquals(Duration.ofDays(30), config.sessionTtl)
        assertTrue(config.isCookieSecure)
    }

    @Test
    fun `a blank issuer or client id means auth disabled, not an error`() {
        // The first-class disabled state: a fresh clone and CI must boot with no
        // tenant provisioned anywhere.
        assertNull(AuthConfig.fromConfig(section(complete - "issuer")))
        assertNull(AuthConfig.fromConfig(section(complete - "client-id")))
        assertNull(AuthConfig.fromConfig(section(complete + ("issuer" to "   "))))
        assertNull(AuthConfig.fromConfig(section(emptyMap())))
    }

    @Test
    fun `a trailing slash on the issuer is stripped`() {
        // Otherwise discovery resolves to a doubled slash and 404s.
        val config = assertNotNull(AuthConfig.fromConfig(section(complete + ("issuer" to "https://tenant.example.com/"))))

        assertEquals("https://tenant.example.com", config.issuer)
    }

    @Test
    fun `a missing client secret is allowed for a public client`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(complete - "client-secret")))

        assertEquals("", config.clientSecret)
    }

    @Test
    fun `provider, ttl and cookie flag are overridable`() {
        val config =
            assertNotNull(
                AuthConfig.fromConfig(
                    section(
                        complete +
                            mapOf(
                                "provider" to "auth0",
                                "session-ttl" to "12h",
                                "cookie-secure" to "false",
                            ),
                    ),
                ),
            )

        assertEquals("auth0", config.provider)
        assertEquals(Duration.ofHours(12), config.sessionTtl)
        assertTrue(!config.isCookieSecure)
    }
}
