package ca.floo.roadtrip.routes

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.service.booking.adapters.recgov.RecGovBookingSessionProvider
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val recgovSessionJson = Json { encodeDefaults = true }

internal fun Route.recGovBookingSessionRoutes(
    session: RecGovBookingSessionProvider,
    dispatchConfig: DispatchConfig,
) {
    get("/api/campsite/booking/session/fresh-token", {
        tags = listOf("campsite-booking")
        summary = "Companion-facing Recreation.gov recaccount JSON"
        response {
            code(HttpStatusCode.OK) { description = "A non-expired recaccount-shaped Recreation.gov session." }
            code(HttpStatusCode.Unauthorized) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        if (!call.requireCompanionAuth(dispatchConfig)) return@get
        val recaccount = session.freshRecaccount()
        if (recaccount == null) {
            call.respondError("no_recgov_recaccount", HttpStatusCode.NotFound)
            return@get
        }
        call.respondJson(recaccount)
    }
}

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(recgovSessionJson.encodeToString(body), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) = respondJson(ApiErrorSchema(error = error, detail = detail), status)
