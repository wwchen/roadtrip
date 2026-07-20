package ca.floo.roadtrip

import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.support.cacheOptionsFor
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
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
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level

private const val API_ACCESS_LOG_PREFIX = "http_api request"
private const val API_HEALTH_PATH = "/api/health"
private const val API_PATH_PREFIX = "/api/"
private const val UNKNOWN_HTTP_STATUS = 0
private const val CLIENT_ERROR_STATUS_MIN = 400
private const val SERVER_ERROR_STATUS_MIN = 500
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val ERROR_INVALID_BODY = "invalid_body"
private const val ERROR_INVALID_PARAMETER = "invalid_parameter"
private const val ERROR_INVALID_REQUEST = "invalid_request"
private const val ERROR_MISSING_PARAMETER = "missing_parameter"
private const val REQUEST_START_NANOS_ATTRIBUTE = "roadtrip.request_start_nanos"

private val accessLog = LoggerFactory.getLogger("Application")
private val requestStartNanosKey = AttributeKey<Long>(REQUEST_START_NANOS_ATTRIBUTE)

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
    install(roadtripAccessLogging)
    install(CallLogging) {
        // Access logs are emitted by RoadtripAccessLogging so 5xx requests can
        // be logged at ERROR. Keep CallLogging installed for per-request MDC.
        level = Level.TRACE
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

private val roadtripAccessLogging =
    createApplicationPlugin("RoadtripAccessLogging") {
        onCall { call ->
            if (shouldLogApi(call)) {
                call.attributes.put(requestStartNanosKey, System.nanoTime())
            }
        }
        on(ResponseSent) { call ->
            if (!shouldLogApi(call)) return@on

            val method = call.request.httpMethod.value
            val path = call.request.path()
            val status = call.response.status()?.value ?: UNKNOWN_HTTP_STATUS
            val durationMs = call.durationMs()
            val message = "$API_ACCESS_LOG_PREFIX $method $path $status ${durationMs}ms"
            withHttpAccessMdc(call, status, durationMs) {
                when (accessLogLevelForStatus(status)) {
                    Level.ERROR -> accessLog.error(message)
                    Level.WARN -> accessLog.warn(message)
                    else -> accessLog.info(message)
                }
            }
        }
    }

internal fun accessLogLevelForStatus(status: Int): Level =
    when {
        status >= SERVER_ERROR_STATUS_MIN -> Level.ERROR
        status >= CLIENT_ERROR_STATUS_MIN -> Level.WARN
        else -> Level.INFO
    }

private fun shouldLogApi(call: ApplicationCall): Boolean {
    val path = call.request.path()
    return path.startsWith(API_PATH_PREFIX) && path != API_HEALTH_PATH
}

private fun ApplicationCall.durationMs(): Long {
    val start = attributes.takeIf { it.contains(requestStartNanosKey) }?.get(requestStartNanosKey) ?: return processingTimeMillis()
    return ((System.nanoTime() - start) / NANOS_PER_MILLISECOND).coerceAtLeast(0)
}

private inline fun withHttpAccessMdc(
    call: ApplicationCall,
    status: Int,
    durationMs: Long,
    block: () -> Unit,
) {
    val entries =
        mapOf(
            "http_method" to call.request.httpMethod.value,
            "http_path" to call.request.path(),
            "http_status" to status.toString(),
            "http_duration_ms" to durationMs.toString(),
            "http_remote" to call.request.local.remoteHost,
        )
    val previous = entries.keys.associateWith { MDC.get(it) }
    try {
        entries.forEach { (key, value) -> MDC.put(key, value) }
        block()
    } finally {
        previous.forEach { (key, value) ->
            if (value == null) {
                MDC.remove(key)
            } else {
                MDC.put(key, value)
            }
        }
    }
}
