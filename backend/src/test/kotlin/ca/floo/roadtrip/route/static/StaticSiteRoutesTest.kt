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
import kotlin.test.assertNotEquals

/**
 * The strangler seam: which tree a page is served from.
 *
 * Worth testing directly because the failure modes are silent. A missing
 * `/assets` mount serves a page whose HTML loads and whose bundles 404, and a
 * fallback that stops working turns an unbuilt checkout into a 404 for a page
 * that works fine on the legacy path.
 */
class StaticSiteRoutesTest {
    private fun legacyTree(): File =
        createTempDirectory("rt-static").toFile().apply {
            File(this, "index.html").writeText(LEGACY_INDEX)
            File(this, "watches.html").writeText(LEGACY_WATCHES)
            File(this, "availability.html").writeText(LEGACY_AVAILABILITY)
        }

    /** A built frontend, as `vite build` leaves it. */
    private fun builtTree(): File =
        createTempDirectory("rt-frontend").toFile().apply {
            File(this, "index.html").writeText(BUILT_MAP)
            File(this, "watches.html").writeText(BUILT_WATCHES)
            File(this, "availability.html").writeText(BUILT_AVAILABILITY)
            File(this, "assets").mkdirs()
            File(this, "assets/watches-abc123.js").writeText(BUNDLE)
        }

    private fun serving(
        staticDir: File,
        frontendDir: File,
        previewPagesEnabled: Boolean = false,
        block: suspend (HttpClient) -> Unit,
    ) = testApplication {
        application { routing { staticSiteRoutes(staticDir, frontendDir, previewPagesEnabled) } }
        block(client)
    }

    @Test
    fun `a migrated page is served from the build, not the legacy tree`() =
        serving(legacyTree(), builtTree()) { client ->
            for (path in listOf("/watches", "/watches.html")) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, path)
                assertEquals(BUILT_WATCHES, response.bodyAsText(), path)
            }
        }

    // availability is migrated too, and both URL forms come from the one
    // `migratedPages` entry — which is what retired the hand-written
    // `get("/availability")` route that used to provide the extensionless alias.
    @Test
    fun `the availability dashboard is served from the build on both URL forms`() =
        serving(legacyTree(), builtTree()) { client ->
            for (path in listOf("/availability", "/availability.html")) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, path)
                assertEquals(BUILT_AVAILABILITY, response.bodyAsText(), path)
            }
        }

    // The catch-all refuses subdirectories, so without its own mount a built page
    // would load its HTML and none of its bundles.
    @Test
    fun `the built assets directory is served`() =
        serving(legacyTree(), builtTree()) { client ->
            val response = client.get("/assets/watches-abc123.js")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(BUNDLE, response.bodyAsText())
        }

    // A sandbox bind-mounts the checkout but pulls a pre-built image, so an
    // unbuilt frontend/dist has to degrade rather than 404.
    @Test
    fun `a migrated page falls back to the legacy file when the build is absent`() =
        serving(legacyTree(), createTempDirectory("rt-frontend-empty").toFile()) { client ->
            for (path in listOf("/watches", "/watches.html")) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.OK, response.status, path)
                assertEquals(LEGACY_WATCHES, response.bodyAsText(), path)
            }
        }

    // watches' legacy file was deleted along with web/watches/, so an unbuilt
    // frontend leaves nothing to serve. respondFile on a missing path throws,
    // which would surface as a 500; a 404 is the honest answer.
    @Test
    fun `a migrated page with neither a build nor a legacy file is a 404`() =
        serving(
            createTempDirectory("rt-static-bare").toFile(),
            createTempDirectory("rt-frontend-bare").toFile(),
        ) { client ->
            for (path in listOf("/watches", "/watches.html")) {
                assertEquals(HttpStatusCode.NotFound, client.get(path).status, path)
            }
        }

    // The map is the last page still on the vanilla tree (Phase 4). It is served by
    // the catch-all's `default`, not by a migrated-page route.
    @Test
    fun `the unmigrated map page is still served from the legacy tree`() =
        serving(legacyTree(), builtTree()) { client ->
            assertEquals(LEGACY_INDEX, client.get("/").bodyAsText())
            assertEquals(LEGACY_INDEX, client.get("/index.html").bodyAsText())
        }

    // A preview URL is a SECOND url for a page still mid-migration, never a
    // replacement: the vanilla map is the only one with a drawer and a topbar, and
    // is what QA of everything else on a sandbox runs against.
    @Test
    fun `a preview page is served alongside the vanilla one when previews are on`() =
        serving(legacyTree(), builtTree(), previewPagesEnabled = true) { client ->
            val preview = client.get("/preview/map")
            assertEquals(HttpStatusCode.OK, preview.status)
            assertEquals(BUILT_MAP, preview.bodyAsText())
            assertEquals(LEGACY_INDEX, client.get("/").bodyAsText())
        }

    // Off by default, because the page it exposes is half-built by definition. A
    // production deployment must not be able to reach it at all.
    //
    // Forbidden rather than Not Found, and not because of anything this route does:
    // with no preview route registered, the path falls through to the catch-all,
    // whose `exclude` refuses everything in a subdirectory. Any `/a/b` URL on the
    // legacy site answers the same way. What matters is that the built page is not
    // what comes back.
    @Test
    fun `a preview page is not reachable when previews are off`() =
        serving(legacyTree(), builtTree()) { client ->
            val response = client.get("/preview/map")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNotEquals(BUILT_MAP, response.bodyAsText())
        }

    // No legacy fallback for a preview URL: the vanilla page is at its own url, so
    // an unbuilt frontend means there is genuinely nothing to serve here.
    @Test
    fun `a preview page with no build is a 404 rather than the legacy page`() =
        serving(
            legacyTree(),
            createTempDirectory("rt-frontend-empty").toFile(),
            previewPagesEnabled = true,
        ) { client ->
            assertEquals(HttpStatusCode.NotFound, client.get("/preview/map").status)
        }

    // frontendDir defaults to frontend/dist under staticDir, which is what makes
    // one default work for both `.` on the host and /app/static in a container.
    @Test
    fun `the frontend directory defaults to frontend-dist under the static dir`() {
        val staticDir = legacyTree()
        File(staticDir, "frontend/dist").mkdirs()
        File(staticDir, "frontend/dist/watches.html").writeText(BUILT_WATCHES)

        testApplication {
            application { routing { staticSiteRoutes(staticDir) } }
            assertEquals(BUILT_WATCHES, client.get("/watches").bodyAsText())
        }
    }

    private companion object {
        const val LEGACY_INDEX = "<html><body>legacy map</body></html>"
        const val LEGACY_WATCHES = "<html><body>legacy watches</body></html>"
        const val LEGACY_AVAILABILITY = "<html><body>legacy availability</body></html>"
        const val BUILT_MAP = """<html><body><div id="root">react map</div></body></html>"""
        const val BUILT_WATCHES = """<html><body><div id="root">watches</div></body></html>"""
        const val BUILT_AVAILABILITY = """<html><body><div id="root">availability</div></body></html>"""
        const val BUNDLE = "console.log('bundle')"
    }
}
