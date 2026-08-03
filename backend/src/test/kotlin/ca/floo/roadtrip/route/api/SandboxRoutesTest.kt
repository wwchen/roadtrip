package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.SandboxConfig
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import ca.floo.roadtrip.repo.UserRepo
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val detachedCtx = DSL.using(SQLDialect.POSTGRES)

class SandboxRoutesTest {
    private val now = OffsetDateTime.now()

    private val willAdmin =
        User(
            id = UserId(90001L),
            email = "will@sandbox.local",
            isEmailVerified = true,
            displayName = "Will",
            status = UserStatus.ACTIVE,
            roles = setOf(Role.ADMIN),
            createdAt = now,
            updatedAt = now,
        )

    private val mattUser =
        User(
            id = UserId(90002L),
            email = "matt@sandbox.local",
            isEmailVerified = true,
            displayName = "Matt",
            status = UserStatus.ACTIVE,
            roles = emptySet(),
            createdAt = now,
            updatedAt = now,
        )

    private fun stubUserRepo(users: List<User>): UserRepo =
        object : UserRepo(ctx = detachedCtx) {
            override fun listSandboxUsers(): List<User> = users
        }

    @Test
    fun `lists users when sandbox enabled`() =
        testApplication {
            application {
                routing {
                    sandboxRoutes(SandboxConfig(assumeUserEnabled = true), stubUserRepo(listOf(willAdmin, mattUser)))
                }
            }
            val resp = client.get("/api/sandbox/users")
            assertEquals(HttpStatusCode.OK, resp.status)
            val arr = Json.parseToJsonElement(resp.bodyAsText()).jsonArray
            assertEquals(2, arr.size)
            val willObj = arr.first { it.jsonObject["id"]!!.jsonPrimitive.content == "90001" }.jsonObject
            val roles = willObj["roles"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue("admin" in roles, "Will should have 'admin' role, got: $roles")
        }

    @Test
    fun `404 when sandbox disabled`() =
        testApplication {
            application {
                routing {
                    sandboxRoutes(SandboxConfig(assumeUserEnabled = false), stubUserRepo(emptyList()))
                }
            }
            assertEquals(HttpStatusCode.NotFound, client.get("/api/sandbox/users").status)
        }
}
