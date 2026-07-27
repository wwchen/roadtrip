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

// The static site is public — every mount is anonymous. `.access(...)` on each
// staticFiles root covers the file-serving leaves it registers beneath it.
internal fun Route.staticSiteRoutes(staticDir: File) {
    staticFiles("/web", File(staticDir, "web"))
        .access(RouteAccess.Anonymous)
    staticFiles("/data", File(staticDir, "data")) {
        exclude { it.path.contains("/raw/") }
        contentType { f ->
            if (f.name.endsWith(".geojson")) ContentType("application", "geo+json") else null
        }
    }.access(RouteAccess.Anonymous)
    get("/availability") {
        call.respondFile(File(staticDir, "availability.html"))
    }.access(RouteAccess.Anonymous)
    get("/watches") {
        call.respondFile(File(staticDir, "watches.html"))
    }.access(RouteAccess.Anonymous)
    staticFiles("/", staticDir) {
        default("index.html")
        exclude { f ->
            val rel = f.relativeTo(staticDir).path
            rel.contains(File.separator)
        }
    }.access(RouteAccess.Anonymous)
}
