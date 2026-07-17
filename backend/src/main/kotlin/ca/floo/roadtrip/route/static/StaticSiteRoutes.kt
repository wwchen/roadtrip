package ca.floo.roadtrip.route.static

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

internal fun Route.staticSiteRoutes(staticDir: File) {
    staticFiles("/web", File(staticDir, "web"))
    staticFiles("/data", File(staticDir, "data")) {
        exclude { it.path.contains("/raw/") }
        contentType { f ->
            if (f.name.endsWith(".geojson")) ContentType("application", "geo+json") else null
        }
    }
    get("/availability") {
        call.respondFile(File(staticDir, "availability.html"))
    }
    get("/availability/") {
        call.respondFile(File(staticDir, "availability.html"))
    }
    get("/watches") {
        call.respondFile(File(staticDir, "watches.html"))
    }
    get("/watches/") {
        call.respondFile(File(staticDir, "watches.html"))
    }
    staticFiles("/", staticDir) {
        default("index.html")
        exclude { f ->
            val rel = f.relativeTo(staticDir).path
            rel.contains(File.separator)
        }
    }
}
