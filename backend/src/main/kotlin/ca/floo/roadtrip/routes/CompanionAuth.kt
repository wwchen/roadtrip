package ca.floo.roadtrip.routes

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.models.api.ApiErrorSchema
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private const val BEARER_AUTH_PREFIX = "Bearer "

private val companionAuthJson = Json { encodeDefaults = true }

internal suspend fun ApplicationCall.requireCompanionAuth(config: DispatchConfig): Boolean {
    val expected = config.companionToken
    val presented = request.header(HttpHeaders.Authorization)?.bearerToken()
    if (presented == null || !tokensMatch(presented, expected)) {
        respondText(
            companionAuthJson.encodeToString(ApiErrorSchema(error = "unauthorized")),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
        )
        return false
    }
    return true
}

private fun String.bearerToken(): String? =
    takeIf { it.startsWith(BEARER_AUTH_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_AUTH_PREFIX.length)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun tokensMatch(
    presented: String,
    expected: String,
): Boolean =
    MessageDigest.isEqual(
        presented.toByteArray(Charsets.UTF_8),
        expected.toByteArray(Charsets.UTF_8),
    )
