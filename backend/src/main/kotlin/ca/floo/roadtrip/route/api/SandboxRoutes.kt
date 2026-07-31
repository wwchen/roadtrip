package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.SandboxConfig
import ca.floo.roadtrip.model.api.SandboxUserDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

internal fun Route.sandboxRoutes(
    config: SandboxConfig,
    userRepo: UserRepo,
) {
    route("/api/sandbox") {
        get("/users") {
            if (!config.assumeUserEnabled) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val users =
                userRepo.listAll().map { u ->
                    SandboxUserDto(
                        id = u.id.value,
                        name = u.displayName ?: u.email,
                        roles = u.roles.map { r -> r.wireValue },
                    )
                }
            call.respondEncodedJson(roadtripApiJson, users)
        }.describeApi("sandbox", "List seeded users the sandbox switcher can assume")
            .access(RouteAccess.Anonymous)
    }
}
