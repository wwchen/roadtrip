package ca.floo.roadtrip.route.auth

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import ca.floo.roadtrip.repo.UserRepo
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private val detachedCtx = DSL.using(SQLDialect.POSTGRES)

/** Minimal stub of [UserRepo] that always returns a pre-seeded user for a specific [UserId]. */
private fun stubUserRepoReturning(userId: UserId): UserRepo {
    val user =
        UserRepo.User(
            id = userId,
            email = "sandbox-user@example.com",
            isEmailVerified = true,
            displayName = "Sandbox User",
            status = UserStatus.ACTIVE,
            roles = setOf(Role.ADMIN),
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now(),
        )
    return object : UserRepo(ctx = detachedCtx) {
        override fun findById(id: UserId): UserRepo.User? = if (id == userId) user else null
    }
}

class AuthRoutesTest {
    @Test
    fun `GET me with sandbox principal and auth off reports user but auth disabled`() =
        testApplication {
            application {
                install(roadtripAuthorization) {
                    resolvePrincipal = { token ->
                        if (token == "sandbox:1") Principal.User(UserId(1L), setOf(Role.ADMIN)) else Principal.Anonymous
                    }
                }
                routing { authRoutes(wiring = null, userRepo = stubUserRepoReturning(UserId(1L))) }
            }
            val resp = client.get("/api/me") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=sandbox:1") }
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(false, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(true, obj["authenticated"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `GET me with no cookie and auth off reports not authenticated`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = { Principal.Anonymous } }
                routing { authRoutes(wiring = null, userRepo = stubUserRepoReturning(UserId(1L))) }
            }
            val resp = client.get("/api/me")
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(false, obj["auth_enabled"]!!.jsonPrimitive.boolean)
            assertEquals(false, obj["authenticated"]!!.jsonPrimitive.boolean)
        }
}
