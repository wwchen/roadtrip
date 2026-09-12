package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.config.BuildInfoConfig
import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.HTTP_FORBIDDEN
import ca.floo.roadtrip.model.api.HTTP_NO_CONTENT
import ca.floo.roadtrip.model.api.HTTP_OK
import ca.floo.roadtrip.model.api.HTTP_UNAUTHORIZED
import ca.floo.roadtrip.model.api.bodyAt
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The endpoint count the plan fixed for this phase; a row added or dropped is a deliberate change. */
private const val CONTRACT_ROW_COUNT = 46

/** The first and last status any HTTP response can carry, and the last 2xx. */
private const val MIN_HTTP_STATUS = 200
private const val MAX_HTTP_STATUS = 599
private const val LAST_SUCCESS_STATUS = 299

/** A status no row declares, so `bodyAt` has to answer null for it. */
private const val UNDECLARED_STATUS = 418

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

    @Test
    fun `every declared status is a real HTTP status`() {
        assertEquals(
            emptyList(),
            ApiContract.endpoints
                .flatMap { row -> (listOf(row.success) + row.errors).map { row.method.wireValue to it } }
                .filterNot { (_, body) -> body.status in MIN_HTTP_STATUS..MAX_HTTP_STATUS }
                .map { (method, body) -> "$method ${body.status}" },
        )
    }

    @Test
    fun `the success body is the only 2xx a row declares`() {
        ApiContract.endpoints.forEach { row ->
            assertTrue(
                row.success.status in HTTP_OK..LAST_SUCCESS_STATUS,
                "${row.method.wireValue} ${row.path}: success declares ${row.success.status}, not a 2xx",
            )
            assertEquals(
                emptyList(),
                row.errors.filter { it.status in HTTP_OK..LAST_SUCCESS_STATUS }.map { it.status },
                "${row.method.wireValue} ${row.path}: a 2xx belongs in success, not errors",
            )
        }
    }

    @Test
    fun `no row declares the same status and class twice`() {
        ApiContract.endpoints.forEach { row ->
            val keys = (listOf(row.success) + row.errors).map { it.status to it.body }
            assertEquals(
                keys.size,
                keys.toSet().size,
                "${row.method.wireValue} ${row.path} declares a duplicate (status, class): " +
                    "${keys.groupBy { it }.filterValues { it.size > 1 }.keys}",
            )
        }
    }

    /**
     * What makes [bodyAt] total. The `(status, class)` case above still allows
     * two *different* classes at one status, and the body check behind every
     * route test asks for one class per status: an ambiguous row would be
     * checked against whichever entry came first.
     */
    @Test
    fun `no row declares two bodies at one status`() {
        ApiContract.endpoints.forEach { row ->
            val statuses = (listOf(row.success) + row.errors).map { it.status }
            assertEquals(
                emptyList(),
                statuses
                    .groupBy { it }
                    .filterValues { it.size > 1 }
                    .keys
                    .toList(),
                "${row.method.wireValue} ${row.path}: bodyAt() cannot answer an ambiguous status",
            )
        }
    }

    /** The ordering that matters: [bodyAt] reaches the success body for its own status. */
    @Test
    fun `bodyAt answers the success body and nothing for an undeclared status`() {
        val row = ApiContract.endpoints.first()
        assertEquals(row.success.body, row.bodyAt(row.success.status)?.body)
        assertNull(row.bodyAt(UNDECLARED_STATUS))
    }

    @Test
    fun `a 204 carries no body`() {
        ApiContract.endpoints
            .flatMap { row -> (listOf(row.success) + row.errors).map { row to it } }
            .filter { (_, body) -> body.status == HTTP_NO_CONTENT }
            .forEach { (row, body) ->
                assertNull(body.body, "${row.method.wireValue} ${row.path}: a 204 cannot carry a body")
            }
    }

    /**
     * The checkable half of the 401/403 rule: the document builder supplies those
     * statuses from the route's access level, and a row states one only where its
     * own handler writes it — which, everywhere in this tree, means
     * [ApiErrorSchema]. A row naming anything else at 401 or 403 is a row that
     * has started restating the access guard in its own vocabulary.
     */
    @Test
    fun `a 401 or 403 on a row is always an ApiErrorSchema`() {
        assertEquals(
            emptyList(),
            ApiContract.endpoints
                .flatMap { row -> row.errors.map { row to it } }
                .filter { (_, body) -> body.status == HTTP_UNAUTHORIZED || body.status == HTTP_FORBIDDEN }
                .filterNot { (_, body) -> body.body == ApiErrorSchema::class }
                .map { (row, body) -> "${row.method.wireValue} ${row.path} ${body.status}" },
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
            ApiContract.endpoints
                .flatMap { listOfNotNull(it.request, it.success.body) + it.errors.mapNotNull { body -> body.body } }
                .distinct()
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
