package ca.floo.roadtrip.routes

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.RecGovRecaccountSchema
import ca.floo.roadtrip.models.api.RecGovSessionImportRequest
import ca.floo.roadtrip.service.booking.adapters.recgov.RecGovBookingSessionStore
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val recgovSessionJson = Json { encodeDefaults = true }

internal fun Route.recGovBookingSessionRoutes(
    session: RecGovBookingSessionStore,
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

    post("/api/campsite/booking/session/import", {
        tags = listOf("campsite-booking")
        summary = "Import companion browser Recreation.gov recaccount JSON"
        request {
            body<RecGovSessionImportRequest> { mediaTypes(ContentType.Application.Json) }
        }
        response {
            code(HttpStatusCode.OK) { body<RecGovRecaccountSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.Unauthorized) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        if (!call.requireCompanionAuth(dispatchConfig)) return@post

        val request = call.receiveJsonOrNull<RecGovSessionImportRequest>()
        val raw = request?.raw?.takeIf { it.isNotBlank() } ?: request?.recaccountJson?.takeIf { it.isNotBlank() }
        if (raw == null) {
            call.respondError("invalid_recgov_recaccount", HttpStatusCode.BadRequest)
            return@post
        }

        val recaccount = session.importRecaccount(raw)
        if (recaccount == null) {
            call.respondError("invalid_recgov_recaccount", HttpStatusCode.BadRequest)
            return@post
        }

        call.respondJson(recaccount)
    }
}

private suspend inline fun <reified T> ApplicationCall.receiveJsonOrNull(): T? =
    runCatching {
        recgovSessionJson.decodeFromString<T>(receiveText())
    }.getOrNull()

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(recgovSessionJson.encodeToString(body), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) = respondJson(ApiErrorSchema(error = error, detail = detail), status)
