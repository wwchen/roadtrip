package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.route.api.docs.apiDocsRoutes
import ca.floo.roadtrip.route.api.geocode.geocodeRoutes
import ca.floo.roadtrip.route.api.health.healthRoutes
import ca.floo.roadtrip.route.auth.authRoutes
import ca.floo.roadtrip.route.static.staticSiteRoutes
import ca.floo.roadtrip.service.health.ReadinessService
import io.ktor.client.request.get
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A [UserRepo] stub that returns null for all lookups — sufficient for coverage-only tests. */
private val noopUserRepo: UserRepo =
    object : UserRepo(ctx = DSL.using(SQLDialect.POSTGRES)) {
        override fun findById(id: UserId): User? = null
    }

/**
 * The mechanism behind RFC 0010's completeness guarantee: every method-leaf
 * route must declare an access level, or [undeclaredAccessRoutes] reports it.
 *
 * The authoritative check against the *live* production tree is the boot guard
 * in `registerKoinRoutes`, which fails the app if any route is unlabelled. This
 * test exercises the walker itself — against real route structures that are
 * awkward for a tree walk (Swagger UI, static file subtrees, chained
 * `describeApi`, redirect-only handlers) and against synthetic edge cases.
 */
class RouteAccessCoverageTest {
    @Test
    fun `a representative slice of real anonymous routes is fully declared`() {
        val undeclared =
            undeclaredRoutes {
                // Swagger UI (framework-generated, exempt) + our labelled openapi.json.
                apiDocsRoutes()
                healthRoutes { ReadinessService.Report(databaseReachable = true) }
                geocodeRoutes(MapboxGeocoder(token = null))
                authRoutes(wiring = null, userRepo = noopUserRepo)
                // Static file mounts register serving leaves beneath each root.
                // Two roots: the legacy tree and the React build (frontend/dist).
                staticSiteRoutes(
                    staticDir = createTempDirectory("rt-access-static").toFile(),
                    frontendDir = createTempDirectory("rt-access-frontend").toFile(),
                )
            }
        assertEquals(emptyList(), undeclared)
    }

    @Test
    fun `an unlabelled method leaf is reported, a labelled sibling is not`() {
        val undeclared =
            undeclaredRoutes {
                get("/labelled") { call.respondText("ok") }.access(RouteAccess.Anonymous)
                get("/forgotten") { call.respondText("ok") }
            }
        assertEquals(listOf("/forgotten"), undeclared)
    }

    @Test
    fun `a group-level declaration covers its child leaves`() {
        val undeclared =
            undeclaredRoutes {
                route("/group") {
                    get("/a") { call.respondText("ok") }
                    get("/b") { call.respondText("ok") }
                }.access(RouteAccess.Anonymous)
            }
        assertEquals(emptyList(), undeclared)
    }

    @Test
    fun `every access level counts as a declaration`() {
        val undeclared =
            undeclaredRoutes {
                get("/anon") { call.respondText("ok") }.access(RouteAccess.Anonymous)
                get("/user") { call.respondText("ok") }.access(RouteAccess.User)
                get("/admin") { call.respondText("ok") }.access(RouteAccess.HasRole(Role.ADMIN))
                get("/signed") { call.respondText("ok") }.access(RouteAccess.Signed)
            }
        assertEquals(emptyList(), undeclared)
    }

    @Test
    fun `Swagger UI assets are exempt while openapi json must be declared`() {
        // apiDocsRoutes labels openapi.json and leaves the Swagger UI subtree to
        // the exemption. Removing the label would surface openapi.json here.
        val undeclared = undeclaredRoutes { apiDocsRoutes() }
        assertTrue(undeclared.isEmpty(), "expected no undeclared routes, got $undeclared")
    }

    /** Mounts [mount] into a test app and returns the undeclared method-leaf paths. */
    private fun undeclaredRoutes(mount: Route.() -> Unit): List<String> {
        lateinit var undeclared: List<String>
        testApplication {
            application {
                routing { mount() }
                undeclared = routingRoot.undeclaredAccessRoutes()
            }
            // Trigger app startup so the routing tree is built before we read it.
            client.get("/__coverage_probe__")
        }
        return undeclared
    }
}
