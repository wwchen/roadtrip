package ca.floo.roadtrip.route.auth

import ca.floo.roadtrip.client.oidc.OidcClient
import ca.floo.roadtrip.config.AuthConfig
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSessionRepo
import ca.floo.roadtrip.service.auth.AuthController
import ca.floo.roadtrip.service.auth.ClaimsDialectRegistry
import ca.floo.roadtrip.service.auth.IdTokenVerifier
import ca.floo.roadtrip.service.auth.IdentityProviderId
import ca.floo.roadtrip.service.auth.IdentityProviderRegistry
import ca.floo.roadtrip.service.auth.OidcIdentityProvider
import ca.floo.roadtrip.service.auth.SessionService
import ca.floo.roadtrip.service.auth.UserProvisioningService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private val detachedCtx = DSL.using(SQLDialect.POSTGRES)

private val stubUserId = UserId(1L)
private val stubUser =
    UserRepo.User(
        id = stubUserId,
        email = "sandbox-user@example.com",
        isEmailVerified = true,
        displayName = "Sandbox User",
        status = UserStatus.ACTIVE,
        roles = setOf(Role.ADMIN),
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )

/** Stub [UserRepo] returning [stubUser] for [stubUserId] and null for everything else. */
private val stubUserRepo: UserRepo =
    object : UserRepo(ctx = detachedCtx) {
        override fun findById(id: UserId): UserRepo.User? = if (id == stubUserId) stubUser else null
    }

/**
 * Stub [SessionService] that resolves a single hard-coded token to [principal],
 * without touching any repo. Extends [SessionService] with a detached DSL
 * context so no DB call is ever issued; only [resolve] is called in the
 * auth-on `/api/me` path.
 */
private class StubSessionService(
    private val token: String,
    private val principal: Principal.User,
) : SessionService(
        userRepo = stubUserRepo,
        userSessionRepo = UserSessionRepo(detachedCtx),
        sessionTtl = Duration.ofHours(1),
    ) {
    override fun resolve(token: String): Principal.User? = if (token == this.token) principal else null
}

private const val AUTH_ON_TOKEN = "valid-session-token"
private val authOnPrincipal = Principal.User(stubUserId, setOf(Role.ADMIN))

/** Minimal [AuthRouteWiring] where only [authController.resolve] is exercised. */
private fun authOnWiring(): AuthRouteWiring {
    val fakeAuthConfig =
        AuthConfig(
            issuer = "https://test.example",
            clientId = "test-client",
            clientSecret = "test-secret-that-is-long-enough",
            provider = "oidc",
            sessionTtl = Duration.ofHours(1),
            isCookieSecure = false,
            realm = "Username-Password-Authentication",
            embeddedDomain = "test.example",
        )
    val sessionService = StubSessionService(AUTH_ON_TOKEN, authOnPrincipal)
    val authController =
        AuthController(
            config = fakeAuthConfig,
            identityProviderRegistry =
                IdentityProviderRegistry(
                    providers =
                        listOf(
                            OidcIdentityProvider(
                                config = fakeAuthConfig,
                                redirectUri = "https://test.example/auth/callback",
                                oidcClient = OidcClient(issuer = "https://test.example"),
                                idTokenVerifier = IdTokenVerifier(clientId = "test-client"),
                                claimsDialect =
                                    ClaimsDialectRegistry
                                        .default()
                                        .forProvider("oidc"),
                            ),
                        ),
                    activeId = IdentityProviderId(OidcIdentityProvider.ID),
                ),
            userProvisioningService = UserProvisioningService(detachedCtx),
            sessionService = sessionService,
        )
    return AuthRouteWiring(
        authController = authController,
        userRepo = stubUserRepo,
        flowSigningKey = ByteArray(32),
        isCookieSecure = false,
        sessionMaxAgeSeconds = 3600,
        appRootUrl = null,
        authClientId = "test-client",
        authDomain = "test.example",
        authRealm = "Username-Password-Authentication",
        redirectUri = "https://test.example/auth/callback",
        providerLabel = null,
        isEmbeddedLogin = true,
    )
}

class AuthRoutesTest {
    // ── auth off ─────────────────────────────────────────────────────────────

    @Test
    fun `GET me with sandbox principal and auth off reports user but auth disabled`() =
        testApplication {
            application {
                install(roadtripAuthorization) {
                    resolvePrincipal = { token ->
                        if (token == "sandbox:1") Principal.User(stubUserId, setOf(Role.ADMIN)) else Principal.Anonymous
                    }
                }
                routing { authRoutes(wiring = null, userRepo = stubUserRepo) }
            }
            val resp = client.get("/api/me") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=sandbox:1") }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(false, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(true, obj["authenticated"]!!.jsonPrimitive.boolean)
            val user = obj["user"]!!.jsonObject
            assertEquals(stubUserId.value, user["id"]!!.jsonPrimitive.long)
            assertEquals(stubUser.email, user["email"]!!.jsonPrimitive.content)
            assertEquals(stubUser.displayName, user["display_name"]!!.jsonPrimitive.contentOrNull)
            assertEquals(stubUser.isEmailVerified, user["email_verified"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `GET me with no cookie and auth off reports not authenticated`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = null, userRepo = stubUserRepo) }
            }
            val resp = client.get("/api/me")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(false, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(false, obj["authenticated"]!!.jsonPrimitive.boolean)
        }

    // ── auth on ──────────────────────────────────────────────────────────────

    @Test
    fun `GET me with valid session and auth on reports authenticated and auth enabled with user fields`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = authOnWiring(), userRepo = stubUserRepo) }
            }
            val resp =
                client.get("/api/me") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$AUTH_ON_TOKEN") }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(true, obj["authenticated"]!!.jsonPrimitive.boolean)
            val user = obj["user"]!!.jsonObject
            assertEquals(stubUserId.value, user["id"]!!.jsonPrimitive.long)
            assertEquals(stubUser.email, user["email"]!!.jsonPrimitive.content)
            assertEquals(stubUser.displayName, user["display_name"]!!.jsonPrimitive.contentOrNull)
            assertEquals(stubUser.isEmailVerified, user["email_verified"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `GET me with no session and auth on reports not authenticated`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = authOnWiring(), userRepo = stubUserRepo) }
            }
            val resp = client.get("/api/me")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(true, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(false, obj["authenticated"]!!.jsonPrimitive.boolean)
        }
}
