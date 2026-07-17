package ca.floo.roadtrip.route.common

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal sealed interface RouteBodyResult<out T> {
    data class Valid<T>(
        val value: T,
    ) : RouteBodyResult<T>

    data class Invalid(
        val detail: String?,
    ) : RouteBodyResult<Nothing>
}

internal inline fun <T, R> RouteBodyResult<T>.mapCatching(transform: (T) -> R): RouteBodyResult<R> =
    when (this) {
        is RouteBodyResult.Invalid -> this
        is RouteBodyResult.Valid ->
            try {
                RouteBodyResult.Valid(transform(value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RouteBodyResult.Invalid(e.message)
            }
    }

internal suspend inline fun <reified T> ApplicationCall.receiveJsonBody(): RouteBodyResult<T> =
    try {
        RouteBodyResult.Valid(receive())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RouteBodyResult.Invalid(e.message)
    }

internal suspend inline fun <reified T> ApplicationCall.decodeTextJsonBody(json: Json): RouteBodyResult<T> =
    try {
        RouteBodyResult.Valid(json.decodeFromString(receiveText()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RouteBodyResult.Invalid(e.message)
    }

internal suspend inline fun <reified T> ApplicationCall.decodeOptionalTextJsonBody(
    json: Json,
    default: () -> T,
): RouteBodyResult<T> {
    val rawBody = receiveText()
    if (rawBody.isBlank()) return RouteBodyResult.Valid(default())
    return try {
        RouteBodyResult.Valid(json.decodeFromString(rawBody))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RouteBodyResult.Invalid(e.message)
    }
}
