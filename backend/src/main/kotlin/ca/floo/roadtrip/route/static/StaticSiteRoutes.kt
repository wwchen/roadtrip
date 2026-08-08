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
 * Pages the React build owns, as filenames under the frontend build directory.
 *
 * A list rather than a branch per page: each strangler phase adds one entry, and
 * both URL forms for a page (`/watches` and `/watches.html`) are registered from
 * it. When the whole site has moved this becomes the catch-all and the legacy
 * mounts below go away.
 *
 * A page listed here may or may not still have a vanilla file in `staticDir`:
 * watches' and availability's were deleted with the rest of their legacy trees, so
 * they are served from the build or not at all. See `migratedPageFile`.
 *
 * Registering both URL forms from this list is also what retired the hand-written
 * `/availability` route that used to sit below: the extensionless alias it existed
 * to provide is exactly what this loop generates.
 */
private val migratedPages = listOf("watches.html", "availability.html")

private const val HTML_SUFFIX = ".html"
private const val LEGACY_WEB_DIR = "web"
private const val DATA_DIR = "data"
private const val ASSETS_PATH = "/assets"

/** `vite build` output, relative to `staticDir` — `.` on the host and
 *  `/app/static` in a container both resolve, so no profile overrides it. */
private const val FRONTEND_DIR = "frontend/dist"
private const val ASSETS_DIR = "assets"
private const val INDEX_FILE = "index.html"
private const val RAW_DATA_SEGMENT = "/raw/"
private const val GEOJSON_SUFFIX = ".geojson"
private val geoJsonContentType = ContentType("application", "geo+json")

// The static site is public — every mount is anonymous. `.access(...)` on each
// staticFiles root covers the file-serving leaves it registers beneath it.
internal fun Route.staticSiteRoutes(
    staticDir: File,
    frontendDir: File = File(staticDir, FRONTEND_DIR),
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
                val file = migratedPageFile(frontendDir, staticDir, page)
                if (file == null) call.respond(HttpStatusCode.NotFound) else call.respondFile(file)
            }.access(RouteAccess.Anonymous)
        }
    }

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
 * The built page, else the legacy one, else null.
 *
 * The legacy fallback covers a checkout whose `frontend/dist` was never built —
 * a sandbox bind-mounts the source tree but pulls a pre-built image, so the
 * build can legitimately be missing. It only helps while a page still HAS a
 * vanilla file, though: watches' was deleted once React replaced it, so an
 * unbuilt frontend now means `/watches` is genuinely unavailable.
 *
 * Hence the null: respondFile on a path that does not exist throws, which would
 * surface as a 500. A 404 is the honest answer for a page with nothing to serve.
 */
private fun migratedPageFile(
    frontendDir: File,
    staticDir: File,
    page: String,
): File? = File(frontendDir, page).takeIf { it.isFile } ?: File(staticDir, page).takeIf { it.isFile }
