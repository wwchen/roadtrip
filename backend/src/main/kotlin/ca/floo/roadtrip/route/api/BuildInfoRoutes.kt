package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.BuildInfoConfig
import ca.floo.roadtrip.model.api.BuildInfoDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

internal fun Route.buildInfoRoutes(config: BuildInfoConfig) {
    route("/api") {
        get("/build-info") {
            call.respondEncodedJson(roadtripApiJson, BuildInfoDto(env = config.env, sha = config.sha, branch = config.branch))
        }.describeApi("build-info", "Identify the running build (env, sha, branch)")
            .access(RouteAccess.Anonymous)
    }
}
