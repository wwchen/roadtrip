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
 * both URL forms for a page (`/watches` and `/watches.html`) are registered from it
 * — see `urlFormsOf`, which is also what makes the root page's second form `/`.
 *
 * **Every page is here, and none has a vanilla file behind it any more.** Phase 5
 * deleted the strangled `web/` app and the root `index.html`, so all three are
 * served from the build or not at all — `migratedPageFile`'s fallback describes the
 * shape of the seam rather than a file that exists today, and it answers 404 rather
 * than 500 when there is nothing to serve.
 *
 * Registering both URL forms from this list is also what retired the hand-written
 * `/availability` route that used to sit below: the extensionless alias it existed
 * to provide is exactly what this loop generates, and `urlFormsOf` is what makes the
 * root page's second form `/` rather than `/index`.
 */
private val migratedPages = listOf("watches.html", "availability.html", INDEX_FILE)

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

    // What survives of `web/`: `design-system/tokens.css` (the colour source of
    // truth, served rather than bundled so a token change needs no frontend build)
    // and the two `sandbox-*` module/stylesheet pairs. Every React page loads all
    // three at runtime — see `frontend/vite/runtime-served-assets.ts`. The rest of
    // the tree was deleted in Phase 5.
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
        for (path in urlFormsOf(page)) {
            get(path) {
                val file = migratedPageFile(frontendDir, staticDir, page)
                if (file == null) call.respond(HttpStatusCode.NotFound) else call.respondFile(file)
            }.access(RouteAccess.Anonymous)
        }
    }

    // No `default(INDEX_FILE)` any more: the root page is a migrated page like every
    // other one, so it is served by the explicit route above. Leaving the default in
    // would put a second claimant on `/` whose file the exclude below refuses anyway.
    // The flat legacy site is gone — this mount now serves whatever loose file sits
    // at the static root (nothing does today) and is what makes a stray path a 403
    // rather than a 404. Kept because the root is a real directory a deploy can drop
    // a file into; delete it the day that stops being true.
    staticFiles("/", staticDir) {
        exclude { f ->
            val rel = f.relativeTo(staticDir).path
            // Subdirectories are not part of it. Migrated pages are excluded so the
            // explicit routes above are the ONLY thing that can serve them: both
            // would otherwise match `/watches.html`, and which one wins would rest
            // on Ktor's resolution scoring rather than on anything stated here.
            rel.contains(File.separator) || rel in migratedPages
        }
    }.access(RouteAccess.Anonymous)
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

/**
 * The built page, else the legacy one, else null.
 *
 * The legacy fallback covered a checkout whose `frontend/dist` was never built — a
 * sandbox bind-mounts the source tree but pulls a pre-built image, so the build can
 * legitimately be missing. As of Phase 5 no page has a vanilla file left, so it
 * never fires: an unbuilt frontend means the site is genuinely unavailable, loudly.
 * It stays because "built, else legacy, else nothing" is the honest description of
 * this seam, and a deploy that does restore a file gets served rather than ignored.
 *
 * Hence the null: respondFile on a path that does not exist throws, which would
 * surface as a 500. A 404 is the honest answer for a page with nothing to serve.
 */
private fun migratedPageFile(
    frontendDir: File,
    staticDir: File,
    page: String,
): File? = File(frontendDir, page).takeIf { it.isFile } ?: File(staticDir, page).takeIf { it.isFile }
