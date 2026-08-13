package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.model.api.PlanningErrorDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.service.planning.PlanningService
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.LocalDate
import java.time.format.DateTimeParseException

internal fun Route.planningRoutes(planningService: PlanningService) {
    route("/api/planning") {
        get("/templates") {
            call.respondEncodedJson(roadtripApiJson, planningService.listTemplates())
        }.describeApi("planning-templates", "List authored trip templates for Planning Mode (RFC 0011)")
            .access(RouteAccess.Anonymous)

        get("/templates/{id}/timeline") {
            val id = call.parameters["id"].orEmpty()
            val startRaw = call.request.queryParameters["start"]
            if (startRaw.isNullOrBlank()) {
                return@get call.respondEncodedJson(
                    roadtripApiJson,
                    PlanningErrorDto(error = "missing required query parameter 'start' (YYYY-MM-DD)"),
                    HttpStatusCode.BadRequest,
                )
            }
            val start =
                try {
                    LocalDate.parse(startRaw)
                } catch (_: DateTimeParseException) {
                    return@get call.respondEncodedJson(
                        roadtripApiJson,
                        PlanningErrorDto(error = "invalid start date '$startRaw' (expected YYYY-MM-DD)"),
                        HttpStatusCode.BadRequest,
                    )
                }
            val timeline =
                planningService.timeline(templateId = id, start = start)
                    ?: return@get call.respondEncodedJson(
                        roadtripApiJson,
                        PlanningErrorDto(error = "unknown template '$id'"),
                        HttpStatusCode.NotFound,
                    )
            call.respondEncodedJson(roadtripApiJson, timeline)
        }.describeApi("planning-timeline", "Instantiate a trip template against a start date")
            .access(RouteAccess.Anonymous)
    }
}
