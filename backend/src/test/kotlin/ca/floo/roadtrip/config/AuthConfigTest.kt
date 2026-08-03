package ca.floo.roadtrip.config

import ca.floo.roadtrip.model.domain.auth.Role
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthConfigTest {
    private val clerk =
        mapOf(
            "provider" to "clerk",
            "providers.clerk.issuer" to "https://clerk.example.com",
            "providers.clerk.client-id" to "client-clerk",
            "providers.clerk.client-secret" to "shh-clerk",
        )
    private val auth0 =
        mapOf(
            "providers.auth0.issuer" to "https://tenant.auth0.example.com",
            "providers.auth0.client-id" to "client-auth0",
            "providers.auth0.client-secret" to "shh-auth0",
        )

    // A complete active block whose issuer host is asserted by the realm /
    // embedded-domain tests below. auth0 is the active vendor here so the
    // issuer host ("tenant.example.com") is unambiguous.
    private val complete =
        mapOf(
            "provider" to "auth0",
            "providers.auth0.issuer" to "https://tenant.example.com",
            "providers.auth0.client-id" to "client-auth0",
            "providers.auth0.client-secret" to "shh-auth0",
        )

    private fun section(values: Map<String, String>) = ConfigSection(values.mapKeys { "roadtrip.auth.${it.key}" }).section("roadtrip.auth")

    @Test
    fun `the active vendor block parses`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk)))

        assertEquals("https://clerk.example.com", config.issuer)
        assertEquals("client-clerk", config.clientId)
        assertEquals("shh-clerk", config.clientSecret)
        assertEquals("clerk", config.provider)
        assertEquals(Duration.ofDays(30), config.sessionTtl)
        assertTrue(config.isCookieSecure)
    }

    @Test
    fun `switching provider selects the other vendor's credentials`() {
        // The whole rollback story: both blocks stay configured, one value flips.
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk + auth0 + ("provider" to "auth0"))))

        assertEquals("auth0", config.provider)
        assertEquals("https://tenant.auth0.example.com", config.issuer)
        assertEquals("client-auth0", config.clientId)
        assertEquals("shh-auth0", config.clientSecret)
    }

    @Test
    fun `an incomplete active block means auth disabled even when the other vendor is complete`() {
        assertNull(AuthConfig.fromConfig(section(auth0 + ("provider" to "clerk"))))
        assertNull(AuthConfig.fromConfig(section(clerk - "providers.clerk.issuer")))
        assertNull(AuthConfig.fromConfig(section(clerk - "providers.clerk.client-id")))
        assertNull(AuthConfig.fromConfig(section(clerk + ("providers.clerk.issuer" to "   "))))
        assertNull(AuthConfig.fromConfig(section(emptyMap())))
    }

    @Test
    fun `a missing client secret means auth disabled, not a public client`() {
        // Confidential client doing a server-side code exchange; the login-flow
        // cookie's signing key derives from the secret.
        assertNull(AuthConfig.fromConfig(section(clerk - "providers.clerk.client-secret")))
        assertNull(AuthConfig.fromConfig(section(clerk + ("providers.clerk.client-secret" to "  "))))
    }

    @Test
    fun `a trailing slash on the issuer is stripped`() {
        // Otherwise discovery resolves to a doubled slash and 404s.
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk + ("providers.clerk.issuer" to "https://clerk.example.com/"))))

        assertEquals("https://clerk.example.com", config.issuer)
    }

    @Test
    fun `ttl and cookie flag stay at the auth level, not per vendor`() {
        val config =
            assertNotNull(
                AuthConfig.fromConfig(
                    section(clerk + mapOf("session-ttl" to "12h", "cookie-secure" to "false")),
                ),
            )

        assertEquals(Duration.ofHours(12), config.sessionTtl)
        assertTrue(!config.isCookieSecure)
    }

    @Test
    fun `realm defaults to the standard Auth0 database connection name`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(complete)))

        assertEquals("Username-Password-Authentication", config.realm)
    }

    @Test
    fun `realm is overridable`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(complete + ("realm" to "MyConnection"))))

        assertEquals("MyConnection", config.realm)
    }

    @Test
    fun `embeddedDomain defaults to the issuer host with scheme stripped`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(complete)))

        // issuer is "https://tenant.example.com" → domain is "tenant.example.com"
        assertEquals("tenant.example.com", config.embeddedDomain)
    }

    @Test
    fun `embeddedDomain is overridable via embedded-domain`() {
        val config =
            assertNotNull(AuthConfig.fromConfig(section(complete + ("embedded-domain" to "auth.roadtrip.floo.ca"))))

        assertEquals("auth.roadtrip.floo.ca", config.embeddedDomain)
    }

    @Test
    fun `role-emails parses an inline array into a lowercased set keyed by role`() {
        val config =
            assertNotNull(
                AuthConfig.fromConfig(
                    // The flattener turns the YAML list into a comma-joined string,
                    // which is exactly what a flat map key holds at this layer.
                    section(clerk + ("role-emails.admin" to "You@Example.com, other@example.com")),
                ),
            )

        assertEquals(mapOf(Role.ADMIN to setOf("you@example.com", "other@example.com")), config.roleGrants)
    }

    @Test
    fun `role-emails skips unknown role keys without crashing`() {
        val config =
            assertNotNull(
                AuthConfig.fromConfig(
                    section(clerk + mapOf("role-emails.admin" to "a@example.com", "role-emails.wizard" to "b@example.com")),
                ),
            )

        assertEquals(mapOf(Role.ADMIN to setOf("a@example.com")), config.roleGrants)
    }

    @Test
    fun `an empty list for a role key yields an empty set for that role`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk + ("role-emails.admin" to ""))))

        assertEquals(mapOf(Role.ADMIN to emptySet()), config.roleGrants)
    }

    @Test
    fun `absent role-emails yields an empty map`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk)))

        assertEquals(emptyMap(), config.roleGrants)
    }
}
