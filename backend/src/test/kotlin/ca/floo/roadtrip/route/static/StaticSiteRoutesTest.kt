package ca.floo.roadtrip.route.static

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which files the static site will serve, and which it will not.
 *
 * Worth testing directly because the failure modes are silent. A missing
 * `/assets` mount serves a page whose HTML loads and whose bundles 404; a
 * catch-all left in place after Phase 5 would serve the repo's own flat files to
 * anyone who guessed a name.
 */
class StaticSiteRoutesTest {
    /**
     * The repo root as Ktor sees it: `data/` plus whatever else happens to sit
     * beside it. `README.md` stands in for "a flat file that is not a page" — on
     * the host `staticDir` really is the checkout.
     */
    private fun staticTree(): File =
        createTempDirectory("rt-static").toFile().apply {
            File(this, "README.md").writeText(NOT_A_PAGE)
            File(this, "data").mkdirs()
            File(this, "data/us-states.geojson").writeText(GEOJSON)
            File(this, "data/raw").mkdirs()
            File(this, "data/raw/dump.json").writeText(RAW_DUMP)
        }

    /** A built frontend, as `vite build` leaves it. */
    private fun builtTree(): File =
        createTempDirectory("rt-frontend").toFile().apply {
            File(this, "index.html").writeText(BUILT_MAP)
            File(this, "watches.html").writeText(BUILT_WATCHES)
            File(this, "availability.html").writeText(BUILT_AVAILABILITY)
            File(this, "poi.html").writeText(BUILT_POI)
            File(this, "assets").mkdirs()
            File(this, "assets/watches-abc123.js").writeText(BUNDLE)
        }

    private fun serving(
        staticDir: File,
        frontendDir: File,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application { routing { staticSiteRoutes(staticDir, frontendDir) } }
        block(client)
    }

    // Both URL forms come from one `pages` entry — which is what retired the
    // hand-written `get("/availability")` route that used to provide the
    // extensionless alias.
    @Test
    fun `every page is served from the build on both URL forms`() =
        serving(staticTree(), builtTree()) { client ->
            val expected =
                mapOf(
                    "/watches" to BUILT_WATCHES,
                    "/watches.html" to BUILT_WATCHES,
                    "/availability" to BUILT_AVAILABILITY,
                    "/availability.html" to BUILT_AVAILABILITY,
                    "/poi" to BUILT_POI,
                    "/poi.html" to BUILT_POI,
                    // The root page's second form is `/`, not the `/index` that
                    // stripping `.html` would produce.
                    "/" to BUILT_MAP,
                    "/index.html" to BUILT_MAP,
                )
            for ((path, body) in expected) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, path)
                assertEquals(body, response.bodyAsText(), path)
            }
        }

    // The `/assets` mount is what makes a built page loadable at all.
    @Test
    fun `the built assets directory is served`() =
        serving(staticTree(), builtTree()) { client ->
            val response = client.get("/assets/watches-abc123.js")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(BUNDLE, response.bodyAsText())
        }

    // Phase 5 deleted `web/`, so there is no vanilla file left to degrade to. An
    // unbuilt frontend is a 404 — not a 500, which is what respondFile on a missing
    // path would produce, and not a 200 serving something stale.
    @Test
    fun `a page with no build is a 404`() =
        serving(staticTree(), createTempDirectory("rt-frontend-empty").toFile()) { client ->
            for (path in listOf("/", "/index.html", "/watches", "/availability")) {
                assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
            }
        }

    // The flat catch-all went with `web/` and the root `index.html`. It is what used
    // to answer `/README.md` on a host where staticDir is the checkout itself, so its
    // absence is a property worth pinning rather than an implementation detail.
    @Test
    fun `nothing outside the declared mounts is served`() =
        serving(staticTree(), builtTree()) { client ->
            for (path in listOf("/README.md", "/preview/map", "/backend/build.gradle.kts")) {
                assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
            }
        }

    // `/data` keeps its `exclude`, so a raw dump is refused with 403 rather than 404:
    // Ktor answers Forbidden when a staticFiles mount matches a path and its exclude
    // rejects it. That is the stronger assertion of the two — it says the mount saw the
    // request and turned it down, where a 404 would also pass if `/data` stopped being
    // served at all. The 404s above are the opposite case, and now genuinely 404: with
    // the catch-all deleted there is no handler at `/` to match them in the first
    // place, which is why `/preview/map` moved from Forbidden to NotFound.
    @Test
    fun `data files are served and raw dumps are refused`() =
        serving(staticTree(), builtTree()) { client ->
            val geoJson = client.get("/data/us-states.geojson")
            assertEquals(HttpStatusCode.OK, geoJson.status)
            assertEquals(GEOJSON, geoJson.bodyAsText())
            assertEquals(HttpStatusCode.Forbidden, client.get("/data/raw/dump.json").status)
        }

    // frontendDir defaults to frontend/dist under staticDir, which is what makes
    // one default work for both `.` on the host and /app/static in a container.
    @Test
    fun `the frontend directory defaults to frontend-dist under the static dir`() {
        val staticDir = staticTree()
        File(staticDir, "frontend/dist").mkdirs()
        File(staticDir, "frontend/dist/watches.html").writeText(BUILT_WATCHES)

        testApplication {
            application { routing { staticSiteRoutes(staticDir) } }
            assertEquals(BUILT_WATCHES, client.get("/watches").bodyAsText())
        }
    }

    private companion object {
        const val NOT_A_PAGE = "# Roadtrip"
        const val GEOJSON = """{"type":"FeatureCollection","features":[]}"""
        const val RAW_DUMP = """{"secret":"upstream dump"}"""
        const val BUILT_MAP = """<html><body><div id="root">react map</div></body></html>"""
        const val BUILT_WATCHES = """<html><body><div id="root">watches</div></body></html>"""
        const val BUILT_AVAILABILITY = """<html><body><div id="root">availability</div></body></html>"""
        const val BUILT_POI = """<html><body><div id="root">poi</div></body></html>"""
        const val BUNDLE = "console.log('bundle')"
    }
}
