package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.config.BuildInfoConfig
import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiFeatureCollectionSchema
import ca.floo.roadtrip.model.api.poi.PoiSearchResponseSchema
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.domain.poi.PoiRow
import ca.floo.roadtrip.route.api.buildInfoRoutes
import ca.floo.roadtrip.route.api.docs.apiDocsRoutes
import ca.floo.roadtrip.route.api.geocode.geocodeRoutes
import ca.floo.roadtrip.route.api.health.healthRoutes
import ca.floo.roadtrip.route.api.pois.poiRoutes
import ca.floo.roadtrip.route.auth.authRoutes
import ca.floo.roadtrip.service.geocode.GeocodeService
import ca.floo.roadtrip.service.health.ReadinessService
import ca.floo.roadtrip.service.poi.PoiReader
import io.ktor.client.request.get
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication
import kotlinx.serialization.serializer
import kotlin.reflect.full.createType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The endpoint count the plan fixed for this phase; a row added or dropped is a deliberate change. */
private const val CONTRACT_ROW_COUNT = 46

/**
 * The contract's *authoritative* check is the boot guard in `registerKoinRoutes`,
 * which compares `ApiContract` against the live tree — including the Slack route,
 * mounted only when a signing secret is configured. This test does what
 * `RouteAccessCoverageTest` does for RFC 0010: it exercises the comparator, against
 * a real mounted slice and against synthetic trees shaped like each failure.
 */
class ApiContractCoverageTest {
    @Test
    fun `a real mounted slice is fully contracted`() {
        assertEquals(emptyList(), driftFor { realMountedSlice() }.uncontractedRoutes)
    }

    /**
     * The same slice, positively: an empty `uncontractedRoutes` alone would also
     * be the answer if the walker lost leaves or dropped the `/auth/password/`
     * prefix. This pins the leaf set — and with it `{id}` rendering.
     */
    @Test
    fun `the real mounted slice yields exactly these leaves`() {
        assertEquals(
            setOf(
                RouteLeaf("POST", "/auth/password/begin"),
                RouteLeaf("POST", "/auth/password/complete"),
                RouteLeaf("GET", "/api/me"),
                RouteLeaf("GET", "/api/health"),
                RouteLeaf("GET", "/api/health/ready"),
                RouteLeaf("GET", "/api/geocode"),
                RouteLeaf("GET", "/api/build-info"),
                RouteLeaf("POST", "/api/pois"),
                RouteLeaf("GET", "/api/pois/search"),
                RouteLeaf("GET", "/api/pois/{id}"),
            ),
            leavesFor { realMountedSlice() },
        )
    }

    @Test
    fun `a mounted route with no contract row is reported`() {
        val drift =
            driftFor {
                get("/api/not-in-the-contract") { call.respondText("ok") }.access(RouteAccess.Anonymous)
            }
        assertEquals(listOf("GET /api/not-in-the-contract"), drift.uncontractedRoutes)
    }

    @Test
    fun `every non-conditional contract row is unmounted in an empty tree`() {
        val drift = driftFor { }
        assertEquals(ApiContract.requiredKeys().size, drift.unmountedRows.size)
        assertTrue(
            drift.unmountedRows.none { it == "POST /api/slack/interactivity" },
            "the conditional Slack row must not be reported as unmounted",
        )
    }

    /**
     * The half the live boot exercises whenever a Slack signing secret is set:
     * `conditional` exempts a row from being *required*, never from being declared.
     */
    @Test
    fun `a mounted conditional row is contracted`() {
        val drift =
            driftFor {
                post("/api/slack/interactivity") { call.respondText("") }.access(RouteAccess.Anonymous)
            }
        assertEquals(emptyList(), drift.uncontractedRoutes)
    }

    @Test
    fun `paths outside the covered prefixes are not contracted`() {
        val drift =
            driftFor {
                get("/auth/login") { call.respondText("ok") }.access(RouteAccess.Anonymous)
                get("/data/x") { call.respondText("ok") }.access(RouteAccess.Anonymous)
            }
        assertEquals(emptyList(), drift.uncontractedRoutes)
    }

    @Test
    fun `the swagger subtree is not contracted`() {
        val leaves = leavesFor { apiDocsRoutes() }
        assertEquals(emptySet(), leaves)
    }

    /** The exemption is the subtree, not everything whose path merely begins with `/api/doc`. */
    @Test
    fun `a sibling path that only shares the docs prefix is contracted`() {
        val drift =
            driftFor {
                get("/api/docsomething") { call.respondText("ok") }.access(RouteAccess.Anonymous)
            }
        assertEquals(listOf("GET /api/docsomething"), drift.uncontractedRoutes)
    }

    /**
     * `get("/api") { … }` mounts a real, reachable handler, and a glob over
     * `/api` includes `/api` itself. A prefix test that only asked
     * `startsWith("/api/")` let both roots serve with no contract row at all.
     */
    @Test
    fun `a leaf at exactly a prefix root is contracted`() {
        val drift =
            driftFor {
                get("/api") { call.respondText("ok") }.access(RouteAccess.Anonymous)
                post("/auth/password") { call.respondText("ok") }.access(RouteAccess.Anonymous)
            }
        assertEquals(listOf("GET /api", "POST /auth/password"), drift.uncontractedRoutes)
    }

    @Test
    fun `the contract declares every row the plan fixed`() = assertEquals(CONTRACT_ROW_COUNT, ApiContract.endpoints.size)

    @Test
    fun `no contract row is declared twice`() {
        val keys = ApiContract.endpoints.map { it.method.wireValue to it.path }
        assertEquals(keys.size, keys.toSet().size, "duplicate rows: ${keys.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }

    @Test
    fun `every contract path is one the guard is answerable for`() {
        assertEquals(
            emptyList(),
            ApiContract.endpoints
                .map { it.path }
                .filterNot(::isContractedPath)
                .sorted(),
        )
    }

    /**
     * `createType()` supplies no type arguments, so a generic DTO would abort this
     * loop with a Kotlin reflection message naming neither the class nor the row.
     * The generator refuses one by name; so does this, rather than failing in the
     * same run with the error the generator exists to replace.
     */
    @Test
    fun `every class the contract names is serializable`() {
        val classes =
            ApiContract.endpoints.flatMap { listOfNotNull(it.request, it.response) + it.errors }.distinct()
        assertEquals(
            emptyList(),
            classes.filter { it.typeParameters.isNotEmpty() }.map { it.qualifiedName },
            "a contract row names a KClass, which carries no type arguments. Declare a concrete DTO " +
                "(e.g. WatchPage) rather than Page<Watch>.",
        )
        classes.forEach { serializer(it.createType()) }
        assertTrue(classes.isNotEmpty(), "the contract must name DTOs for the generator to walk")
    }

    /** The slice both real-tree cases mount: real route functions, stub dependencies. */
    private fun Route.realMountedSlice() {
        apiDocsRoutes()
        authRoutes(wiring = null)
        healthRoutes { ReadinessService.Report(databaseReachable = true) }
        geocodeRoutes(GeocodeService(MapboxGeocoder(token = null)))
        buildInfoRoutes(BuildInfoConfig(env = "test", sha = "0", branch = "test"))
        poiRoutes(EmptyPoiReader)
    }

    private fun leavesFor(mount: Route.() -> Unit): Set<RouteLeaf> {
        lateinit var leaves: Set<RouteLeaf>
        withMountedTree(mount) { leaves = it.contractedApiLeaves() }
        return leaves
    }

    private fun driftFor(mount: Route.() -> Unit): ContractDrift {
        lateinit var drift: ContractDrift
        withMountedTree(mount) { drift = it.apiContractDrift() }
        return drift
    }

    private fun withMountedTree(
        mount: Route.() -> Unit,
        read: (RoutingNode) -> Unit,
    ) {
        testApplication {
            application {
                routing { mount() }
                read(routingRoot)
            }
            client.get("/__contract_probe__")
        }
    }

    /** The contract cares about the routing tree's shape, never about what a handler answers. */
    private object EmptyPoiReader : PoiReader {
        override fun pois(
            bbox: Bbox,
            zoom: Int?,
            categories: List<String>?,
        ): PoiFeatureCollectionSchema = PoiFeatureCollectionSchema(truncated = false, features = emptyList())

        override fun poisWithinPolygon(
            polygonGeoJson: String,
            categories: List<String>?,
        ): List<PoiRow> = emptyList()

        override fun poiDetail(id: Long): PoiDetailFeatureSchema? = null

        override fun search(
            query: String,
            categories: List<String>,
            limit: Int,
        ): PoiSearchResponseSchema = PoiSearchResponseSchema(results = emptyList())
    }
}
