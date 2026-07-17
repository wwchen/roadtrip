package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.model.api.ApiErrorSchema
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
internal val roadtripApiJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

internal suspend fun ApplicationCall.respondApiError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondText(
        roadtripApiJson.encodeToString(ApiErrorSchema(error = error, detail = detail)),
        ContentType.Application.Json,
        status,
    )
}
