package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.route.auth.SESSION_COOKIE
import ca.floo.roadtrip.route.auth.roadtripAuthorization
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The enforcement half of RFC 0010: [roadtripAuthorization] resolves the
 * ambient principal, and `.access(...)` refuses a caller that does not meet the
 * declared level. Deny-capable levels are unused by any real route in PR 1, so
 * this drives them on synthetic routes.
 */
class RouteAccessEnforcementTest {
    // Maps the fake session tokens above to principals; anything else is anonymous.
    private fun resolve(token: String?): Principal =
        when (token) {
            ADMIN_TOKEN -> Principal.User(UserId(1), roles = setOf(Role.ADMIN))
            USER_TOKEN -> Principal.User(UserId(2), roles = emptySet())
            else -> Principal.Anonymous
        }

    private fun ApplicationTestBuilder.installGatedApp() {
        application {
            install(roadtripAuthorization) { resolvePrincipal = ::resolve }
            routing {
                get("/public") { call.respondText("public") }.access(RouteAccess.Anonymous)
                get("/user") { call.respondText("user") }.access(RouteAccess.User)
                get("/admin") { call.respondText("admin") }.access(RouteAccess.HasRole(Role.ADMIN))
                get("/whoami") {
                    val label =
                        when (val p = call.principal()) {
                            is Principal.User -> "user:${p.userId.value}"
                            Principal.Anonymous -> "anonymous"
                            Principal.System -> "system"
                        }
                    call.respondText(label)
                }.access(RouteAccess.Anonymous)
            }
        }
    }

    private suspend fun io.ktor.client.HttpClient.getWith(
        path: String,
        token: String?,
    ) = get(path) { token?.let { header(HttpHeaders.Cookie, "$SESSION_COOKIE=$it") } }

    @Test
    fun `anonymous route never gates, regardless of session`() =
        testApplication {
            installGatedApp()
            assertEquals(HttpStatusCode.OK, client.getWith("/public", token = null).status)
            assertEquals(HttpStatusCode.OK, client.getWith("/public", USER_TOKEN).status)
        }

    @Test
    fun `user route is 401 without a session and 200 with one`() =
        testApplication {
            installGatedApp()
            assertEquals(HttpStatusCode.Unauthorized, client.getWith("/user", token = null).status)
            assertEquals(HttpStatusCode.OK, client.getWith("/user", USER_TOKEN).status)
            assertEquals(HttpStatusCode.OK, client.getWith("/user", ADMIN_TOKEN).status)
        }

    @Test
    fun `role route is 401 anonymous, 403 without the role, 200 with it`() =
        testApplication {
            installGatedApp()
            assertEquals(HttpStatusCode.Unauthorized, client.getWith("/admin", token = null).status)
            assertEquals(HttpStatusCode.Forbidden, client.getWith("/admin", USER_TOKEN).status)
            assertEquals(HttpStatusCode.OK, client.getWith("/admin", ADMIN_TOKEN).status)
        }

    @Test
    fun `the principal is resolved and ambient even on an anonymous route`() =
        testApplication {
            installGatedApp()
            assertEquals("anonymous", client.getWith("/whoami", token = null).bodyAsText())
            assertEquals("user:1", client.getWith("/whoami", ADMIN_TOKEN).bodyAsText())
            assertEquals("user:2", client.getWith("/whoami", USER_TOKEN).bodyAsText())
        }

    private companion object {
        const val ADMIN_TOKEN = "admin-token"
        const val USER_TOKEN = "user-token"
    }
}
