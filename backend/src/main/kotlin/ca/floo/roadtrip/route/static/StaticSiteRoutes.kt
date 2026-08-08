package ca.floo.roadtrip.route.static

import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

/**
 * Pages the React build owns, as filenames under the frontend build directory.
 *
 * A list rather than a branch per page: each strangler phase adds one entry, and
 * both URL forms for a page (`/watches` and `/watches.html`) are registered from
 * it. When the whole site has moved this becomes the catch-all and the legacy
 * mounts below go away.
 */
private val migratedPages = listOf("watches.html")

private const val HTML_SUFFIX = ".html"
private const val LEGACY_WEB_DIR = "web"
private const val DATA_DIR = "data"
private const val ASSETS_PATH = "/assets"
private const val ASSETS_DIR = "assets"
private const val INDEX_FILE = "index.html"
private const val AVAILABILITY_FILE = "availability.html"
private const val RAW_DATA_SEGMENT = "/raw/"
private const val GEOJSON_SUFFIX = ".geojson"
private val geoJsonContentType = ContentType("application", "geo+json")

// The static site is public — every mount is anonymous. `.access(...)` on each
// staticFiles root covers the file-serving leaves it registers beneath it.
internal fun Route.staticSiteRoutes(
    staticDir: File,
    frontendDir: File,
) {
    // Hashed bundles, fonts, and the icon sprite emitted by `vite build`. This
    // mount is what makes a built page loadable at all: the catch-all at the
    // bottom deliberately refuses anything in a subdirectory, so `/assets/*`
    // would otherwise 404 even with the HTML served correctly.
    staticFiles(ASSETS_PATH, File(frontendDir, ASSETS_DIR))
        .access(RouteAccess.Anonymous)

    staticFiles("/$LEGACY_WEB_DIR", File(staticDir, LEGACY_WEB_DIR))
        .access(RouteAccess.Anonymous)
    staticFiles("/$DATA_DIR", File(staticDir, DATA_DIR)) {
        exclude { it.path.contains(RAW_DATA_SEGMENT) }
        contentType { f ->
            if (f.name.endsWith(GEOJSON_SUFFIX)) geoJsonContentType else null
        }
    }.access(RouteAccess.Anonymous)

    // Migrated pages, before the catch-all so an exact path wins over it.
    for (page in migratedPages) {
        val extensionless = "/${page.removeSuffix(HTML_SUFFIX)}"
        for (path in listOf("/$page", extensionless)) {
            get(path) {
                call.respondFile(migratedPageFile(frontendDir, staticDir, page))
            }.access(RouteAccess.Anonymous)
        }
    }

    get("/${AVAILABILITY_FILE.removeSuffix(HTML_SUFFIX)}") {
        call.respondFile(File(staticDir, AVAILABILITY_FILE))
    }.access(RouteAccess.Anonymous)

    staticFiles("/", staticDir) {
        default(INDEX_FILE)
        exclude { f ->
            val rel = f.relativeTo(staticDir).path
            // Subdirectories are not part of the flat legacy site. Migrated pages
            // are excluded so the explicit routes above are the ONLY thing that
            // can serve them: both would otherwise match `/watches.html`, and
            // which one wins would rest on Ktor's resolution scoring rather than
            // on anything stated here.
            rel.contains(File.separator) || rel in migratedPages
        }
    }.access(RouteAccess.Anonymous)
}

/**
 * The built page when the frontend has been built, else the legacy one.
 *
 * The fallback is load-bearing rather than defensive. A sandbox pulls a
 * pre-built backend image but bind-mounts the source tree, so a checkout whose
 * `frontend/dist` was never built would otherwise serve a 404 for a page that
 * works fine on the legacy path. Degrading to the vanilla page keeps a missing
 * or failed build from taking the route down.
 */
private fun migratedPageFile(
    frontendDir: File,
    staticDir: File,
    page: String,
): File {
    val built = File(frontendDir, page)
    return if (built.isFile) built else File(staticDir, page)
}
