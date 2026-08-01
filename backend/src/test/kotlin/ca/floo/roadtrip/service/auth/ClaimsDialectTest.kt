package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.VerifiedIdToken
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun token(
    subject: String,
    claims: Map<String, Any?> = emptyMap(),
    email: String? = "user@example.com",
) = VerifiedIdToken(
    subject = subject,
    issuer = "https://tenant.example.com",
    email = email,
    isEmailVerified = true,
    name = "User",
    claims = claims,
)

class Auth0ClaimsDialectTest {
    private val dialect = Auth0ClaimsDialect()

    @Test
    fun `google connection splits into provider and upstream subject`() {
        val claims = dialect.toIdentityClaims(token("google-oauth2|103547991597142817347"))

        assertEquals("google-oauth2|103547991597142817347", claims.subject)
        assertEquals("google", claims.upstreamProvider)
        assertEquals("103547991597142817347", claims.upstreamSubject)
    }

    @Test
    fun `apple connection normalizes to the apple slug`() {
        val claims = dialect.toIdentityClaims(token("apple|001234.abcdef"))

        assertEquals("apple", claims.upstreamProvider)
        assertEquals("001234.abcdef", claims.upstreamSubject)
    }

    @Test
    fun `the auth0 database connection is reported as a password identity`() {
        val claims = dialect.toIdentityClaims(token("auth0|65a1f0c9"))

        assertEquals("password", claims.upstreamProvider)
        assertEquals("65a1f0c9", claims.upstreamSubject)
    }

    @Test
    fun `an unknown connection passes through lowercased rather than being dropped`() {
        // An enterprise connection is still a stable upstream identity worth
        // recording, even though we have no slug for it.
        val claims = dialect.toIdentityClaims(token("MyCorp-SAML|abc123"))

        assertEquals("mycorp-saml", claims.upstreamProvider)
        assertEquals("abc123", claims.upstreamSubject)
    }

    @Test
    fun `a subject with no separator yields no upstream identity`() {
        val claims = dialect.toIdentityClaims(token("opaque-subject"))

        assertEquals("opaque-subject", claims.subject)
        assertNull(claims.upstreamProvider)
        assertNull(claims.upstreamSubject)
    }

    @Test
    fun `an upstream subject containing a pipe is not truncated`() {
        val claims = dialect.toIdentityClaims(token("google-oauth2|abc|def"))

        assertEquals("abc|def", claims.upstreamSubject)
    }
}

class WorkOsClaimsDialectTest {
    private val dialect = WorkOsClaimsDialect()

    @Test
    fun `connection type and upstream id are read from separate claims`() {
        val claims =
            dialect.toIdentityClaims(
                token("user_01HXYZ", mapOf("connection_type" to "GoogleOAuth", "idp_id" to "google-99")),
            )

        assertEquals("user_01HXYZ", claims.subject)
        assertEquals("google", claims.upstreamProvider)
        assertEquals("google-99", claims.upstreamSubject)
    }

    @Test
    fun `missing claims degrade to no upstream identity rather than failing`() {
        // The claim names are unconfirmed against a live tenant (RFC 0009 open
        // question). Sign-in must still work if they are wrong.
        val claims = dialect.toIdentityClaims(token("user_01HXYZ"))

        assertEquals("user_01HXYZ", claims.subject)
        assertNull(claims.upstreamProvider)
        assertNull(claims.upstreamSubject)
    }

    @Test
    fun `a provider without a stable subject is not treated as a join key`() {
        val claims = dialect.toIdentityClaims(token("user_01HXYZ", mapOf("connection_type" to "GoogleOAuth")))

        assertNull(claims.upstreamProvider)
        assertNull(claims.upstreamSubject)
    }
}

class StandardClaimsDialectTest {
    @Test
    fun `standard dialect reports no upstream identity`() {
        val claims = StandardClaimsDialect().toIdentityClaims(token("sub-1", mapOf("idp_id" to "ignored")))

        assertEquals("sub-1", claims.subject)
        assertNull(claims.upstreamProvider)
        assertNull(claims.upstreamSubject)
    }
}

class ClerkClaimsDialectTest {
    private val dialect = ClerkClaimsDialect()

    @Test
    fun `clerk subjects are opaque and carry no upstream identity`() {
        // Clerk's sub is `user_…` with no embedded connection; migrated
        // accounts link on verified email instead (spec: email relink).
        val claims = dialect.toIdentityClaims(token("user_2abcDEF123"))

        assertEquals("user_2abcDEF123", claims.subject)
        assertEquals("user@example.com", claims.email)
        assertEquals("User", claims.displayName)
        assertNull(claims.upstreamProvider)
        assertNull(claims.upstreamSubject)
    }

    @Test
    fun `vendor-specific claims are ignored rather than misread as upstream identity`() {
        val claims = dialect.toIdentityClaims(token("user_2abcDEF123", mapOf("idp_id" to "ignored")))

        assertNull(claims.upstreamProvider)
        assertNull(claims.upstreamSubject)
    }
}

class ClaimsDialectRegistryTest {
    private val registry = ClaimsDialectRegistry.default()

    @Test
    fun `each known slug selects its dialect`() {
        assertEquals(Auth0ClaimsDialect.ID, registry.forProvider("auth0").id)
        assertEquals(ClerkClaimsDialect.ID, registry.forProvider("clerk").id)
        assertEquals(WorkOsClaimsDialect.ID, registry.forProvider("workos").id)
        assertEquals(StandardClaimsDialect.ID, registry.forProvider("oidc").id)
    }

    @Test
    fun `an unknown slug falls back to standard instead of failing startup`() {
        assertEquals(StandardClaimsDialect.ID, registry.forProvider("typo-provider").id)
    }

    @Test
    fun `display names are human-readable vendor brands`() {
        assertEquals("Auth0", registry.displayNameFor("auth0"))
        assertEquals("Clerk", registry.displayNameFor("clerk"))
        assertEquals("WorkOS", registry.displayNameFor("workos"))
    }

    @Test
    fun `plain oidc and unknown slugs have no display name`() {
        // Null lets the frontend fall back to its generic "single sign-on"
        // copy instead of rendering a raw config slug at the user.
        assertNull(registry.displayNameFor("oidc"))
        assertNull(registry.displayNameFor("typo-provider"))
    }
}
