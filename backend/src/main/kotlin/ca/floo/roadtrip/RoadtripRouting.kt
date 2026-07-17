package ca.floo.roadtrip

import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.support.cacheOptionsFor
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.MissingRequestParameterException
import io.ktor.server.plugins.ParameterConversionException
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.IgnoreTrailingSlash
import org.slf4j.event.Level

private const val API_ACCESS_LOG_PREFIX = "http_api request"
private const val API_HEALTH_PATH = "/api/health"
private const val API_PATH_PREFIX = "/api/"
private const val UNKNOWN_HTTP_STATUS = 0
private const val ERROR_INVALID_BODY = "invalid_body"
private const val ERROR_INVALID_PARAMETER = "invalid_parameter"
private const val ERROR_INVALID_REQUEST = "invalid_request"
private const val ERROR_MISSING_PARAMETER = "missing_parameter"

internal fun Application.installRoadtripPlugins() {
    install(ContentNegotiation) {
        json(roadtripApiJson)
    }
    install(RequestValidation) {}
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respondApiError(
                ERROR_INVALID_REQUEST,
                HttpStatusCode.BadRequest,
                cause.reasons.joinToString("; "),
            )
        }
        exception<MissingRequestParameterException> { call, cause ->
            call.respondApiError(
                ERROR_MISSING_PARAMETER,
                HttpStatusCode.BadRequest,
                "${cause.parameterName} is required",
            )
        }
        exception<ParameterConversionException> { call, cause ->
            call.respondApiError(
                ERROR_INVALID_PARAMETER,
                HttpStatusCode.BadRequest,
                "${cause.parameterName} must be ${cause.type}",
            )
        }
        exception<ContentTransformationException> { call, cause ->
            call.respondApiError(ERROR_INVALID_BODY, HttpStatusCode.BadRequest, cause.message)
        }
        exception<BadRequestException> { call, cause ->
            call.respondApiError(ERROR_INVALID_REQUEST, HttpStatusCode.BadRequest, cause.message)
        }
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            val path = call.request.path()
            path.startsWith(API_PATH_PREFIX) && path != API_HEALTH_PATH
        }
        format { call ->
            val method = call.request.httpMethod.value
            val path = call.request.path()
            val status = call.response.status()?.value ?: UNKNOWN_HTTP_STATUS
            val durationMs = call.processingTimeMillis()
            "$API_ACCESS_LOG_PREFIX $method $path $status ${durationMs}ms"
        }
        mdc("http_method") { call -> call.request.httpMethod.value }
        mdc("http_path") { call -> call.request.path() }
        mdc("http_status") { call -> (call.response.status()?.value ?: UNKNOWN_HTTP_STATUS).toString() }
        mdc("http_duration_ms") { call -> call.processingTimeMillis().toString() }
        mdc("http_remote") { call -> call.request.local.remoteHost }
    }
    install(IgnoreTrailingSlash)
    install(ConditionalHeaders)
    install(Compression) {
        gzip {
            minimumSize(1024)
            matchContentType(
                ContentType.Text.Html,
                ContentType.Text.CSS,
                ContentType.Application.Json,
                ContentType.Application.JavaScript,
                ContentType("application", "geo+json"),
                ContentType.Image.SVG,
            )
        }
    }
    install(CachingHeaders) {
        options { call, content ->
            cacheOptionsFor(call.request.path(), content.contentType)
        }
    }
}
