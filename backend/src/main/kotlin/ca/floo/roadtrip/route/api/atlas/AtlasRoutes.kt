package ca.floo.roadtrip.route.api.atlas

import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.route.common.trimmedQuery
import ca.floo.roadtrip.service.atlas.AtlasService
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

// GET /api/atlas/node?path={dot.delimited.key}
//
// Returns the children of one node in the Atlas index tree. Empty path returns
// the top-level region list; the client expands one level at a time.
internal fun Route.atlasRoutes(atlasService: AtlasService) {
    route("/api/atlas") {
        get("/node") {
            call.respondEncodedJson(
                roadtripApiJson,
                atlasService.node(call.trimmedQuery("path")),
            )
        }.describeApi(
            tag = "atlas",
            summary = "Children of an Atlas index node (region -> classification -> campground -> campsite)",
            description = "Query `path` is a dot-delimited node key; empty returns the top-level region list.",
        ).access(RouteAccess.Anonymous)
    }
}
