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
    respondEncodedJson(
        ApiErrorSchema(error = error, detail = detail),
        status,
    )
}

internal suspend inline fun <reified T> ApplicationCall.respondEncodedJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(
        encodeApiJson(value),
        ContentType.Application.Json,
        status,
    )
}

/** The wire form of any API DTO. Response serialization belongs to routes, so this is the one encoder. */
internal inline fun <reified T> encodeApiJson(value: T): String = roadtripApiJson.encodeToString(value)
