package ca.floo.roadtrip.route.static

import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

/**
 * The pages the site serves, as filenames under the frontend build directory.
 *
 * A list rather than a branch per page: both URL forms for a page (`/watches` and
 * `/watches.html`) are registered from it — see `urlFormsOf`, which is also what
 * makes the root page's second form `/` itself rather than `/index`.
 *
 * Registering both forms from this list is what retired the hand-written
 * `/availability` route that used to sit below: the extensionless alias it existed
 * to provide is exactly what this loop generates.
 *
 * **These routes plus `/assets` and `/data` are now the whole static surface.** The
 * `/web` mount is gone — the three files still under it moved into the React tree —
 * and so is the flat catch-all that used to serve the vanilla site. Nothing resolves
 * by Ktor's scoring any more, and nothing under `staticDir` is reachable except
 * through a mount named here.
 */
private val pages = listOf("watches.html", "availability.html", GALLERY_FILE, INDEX_FILE)

private const val HTML_SUFFIX = ".html"
private const val DATA_DIR = "data"
private const val ASSETS_PATH = "/assets"

/** `vite build` output, relative to `staticDir` — `.` on the host and
 *  `/app/static` in a container both resolve, so no profile overrides it. */
private const val FRONTEND_DIR = "frontend/dist"
private const val ASSETS_DIR = "assets"
private const val INDEX_FILE = "index.html"
private const val GALLERY_FILE = "gallery.html"
private const val RAW_DATA_SEGMENT = "/raw/"
private const val GEOJSON_SUFFIX = ".geojson"
private val geoJsonContentType = ContentType("application", "geo+json")

// The static site is public — every mount is anonymous. `.access(...)` on each
// staticFiles root covers the file-serving leaves it registers beneath it.
internal fun Route.staticSiteRoutes(
    staticDir: File,
    frontendDir: File = File(staticDir, FRONTEND_DIR),
) {
    // Hashed bundles, fonts, and the icon sprite emitted by `vite build`. Without
    // this mount a page would load its HTML and none of its assets.
    staticFiles(ASSETS_PATH, File(frontendDir, ASSETS_DIR))
        .access(RouteAccess.Anonymous)

    // The GeoJSON overlays (state boundaries) and the imported source data. The one
    // static tree that is repo data rather than build output, which is why it
    // outlived `web/`: `map/state-lines.ts` fetches `/data/us-states.geojson` at
    // runtime, and bundling multi-megabyte geometry into the page would be worse.
    staticFiles("/$DATA_DIR", File(staticDir, DATA_DIR)) {
        exclude { it.path.contains(RAW_DATA_SEGMENT) }
        contentType { f ->
            if (f.name.endsWith(GEOJSON_SUFFIX)) geoJsonContentType else null
        }
    }.access(RouteAccess.Anonymous)

    // Migrated pages, before the catch-all so an exact path wins over it.
    // No legacy fallback left to try: an unbuilt `frontend/dist` means the page
    // genuinely has nothing to serve, and every deploy path builds it (`tilt up`,
    // `make run`, and `scripts/deploy.sh`, which fails loudly without npm).
    // Answered as a 404 rather than by handing respondFile a path that does not
    // exist, which throws and would surface as a 500.
    //
    // The flat catch-all that used to sit below is deleted. It was kept "because the
    // root is a real directory a deploy can drop a file into", but that cuts the
    // other way: `staticDir` is `/app/static` in a container and the CHECKOUT ITSELF
    // on a host, where it answered `/README.md`, `/Makefile` and `/gradlew`. Nothing
    // needs it — every page is explicit, `/assets` and `/data` have their own mounts
    // — so the exposure bought nothing. Its absence is pinned by a test.
    for (page in pages) {
        for (path in urlFormsOf(page)) {
            get(path) {
                val file = File(frontendDir, page).takeIf { it.isFile }
                if (file == null) call.respond(HttpStatusCode.NotFound) else call.respondFile(file)
            }.access(RouteAccess.Anonymous)
        }
    }
}

/**
 * Both URLs a page answers on.
 *
 * `/watches.html` and `/watches` for a named page; for the root page the second form
 * is `/` itself rather than the `/index` that stripping the suffix would produce —
 * nothing has ever linked to `/index`.
 */
private fun urlFormsOf(page: String): List<String> =
    listOf(
        "/$page",
        if (page == INDEX_FILE) "/" else "/${page.removeSuffix(HTML_SUFFIX)}",
    )
