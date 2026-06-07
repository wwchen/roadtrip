package ca.floo.roadtrip.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

// /api/superchargers — serves data/tesla-superchargers.geojson as the
// canonical Supercharger feed. The frontend's invariant is "no /data/* reads
// from the FE; everything goes through /api/*", so this is a thin
// passthrough until the postgres-backed version (RFC 0005) replaces it.
//
// The file is pre-filtered to OPEN-only at fetch time
// (scripts/fetch_tesla_superchargers.py); no further filtering here.
fun Route.superchargersRoutes(dataDir: File) {
    get("/api/superchargers") {
        val file = File(dataDir, "tesla-superchargers.geojson")
        if (!file.isFile) {
            // Cold-start: no fetch has run yet. Empty FeatureCollection is
            // the correct "no data" shape; the frontend's count UI handles it.
            call.respondText(
                """{"type":"FeatureCollection","features":[]}""",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
            return@get
        }
        call.respondText(file.readText(), ContentType.Application.Json)
    }
}
