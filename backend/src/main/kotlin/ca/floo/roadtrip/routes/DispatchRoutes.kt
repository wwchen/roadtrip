package ca.floo.roadtrip.routes

import ca.floo.roadtrip.config.DispatchConfig
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.DEFAULT_DISPATCH_KIND
import ca.floo.roadtrip.models.api.DispatchClaimRequest
import ca.floo.roadtrip.models.api.DispatchClaimedResponse
import ca.floo.roadtrip.models.api.DispatchClaimedSchema
import ca.floo.roadtrip.models.api.DispatchCompleteRequest
import ca.floo.roadtrip.models.api.DispatchFailRequest
import ca.floo.roadtrip.models.api.DispatchHeartbeatRequest
import ca.floo.roadtrip.models.api.DispatchLeaseResponse
import ca.floo.roadtrip.models.api.DispatchMutationResponse
import ca.floo.roadtrip.models.api.DispatchQueuedResponse
import ca.floo.roadtrip.models.api.DispatchQueuedSchema
import ca.floo.roadtrip.models.api.DispatchTestEventRequest
import ca.floo.roadtrip.service.availability.DispatchClaimSelector
import ca.floo.roadtrip.service.availability.DispatchClaimed
import ca.floo.roadtrip.service.availability.DispatchCompleteOutcome
import ca.floo.roadtrip.service.availability.DispatchFailResult
import ca.floo.roadtrip.service.availability.DispatchLeaseResult
import ca.floo.roadtrip.service.availability.DispatchQueued
import ca.floo.roadtrip.service.availability.DispatchService
import ca.floo.roadtrip.service.availability.DispatchTestEventInput
import ca.floo.roadtrip.service.availability.DispatchTestEventService
import ca.floo.roadtrip.service.availability.normalizeDispatchKey
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Duration

private const val STATUS_COMPLETED = "completed"
private const val STATUS_FAILED = "failed"
private const val SIMULATE_RESULT_SUCCESS = "success"
private const val SIMULATE_RESULT_FAILURE = "failure"
private const val TEST_DISPATCH_KIND = "test"

private val validSimulateResults = setOf(SIMULATE_RESULT_SUCCESS, SIMULATE_RESULT_FAILURE)

@OptIn(ExperimentalSerializationApi::class)
private val dispatchJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

internal fun Route.dispatchRoutes(
    dispatches: DispatchService,
    testEvents: DispatchTestEventService,
    dispatchConfig: DispatchConfig,
) {
    post("/api/dispatches/claim", {
        tags = listOf("dispatches")
        summary = "Long-poll for a companion dispatch"
        request { body<DispatchClaimRequest> { mediaTypes(ContentType.Application.Json) } }
        response {
            code(HttpStatusCode.OK) { body<DispatchClaimedResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NoContent) { description = "No matching dispatch before the wait expired." }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        if (!call.requireCompanionAuth(dispatchConfig)) return@post
        val req = call.decodeBody<DispatchClaimRequest>() ?: return@post
        val selector =
            runCatching {
                DispatchClaimSelector.ofKinds(
                    req.claimKinds(),
                    req.vendors,
                    req.payloadVersions,
                )
            }.getOrElse {
                return@post call.respondError("invalid_selector", HttpStatusCode.BadRequest, it.message)
            }
        val claimed =
            dispatches.claim(
                selector = selector,
                wait = req.waitSec?.let(Duration::ofSeconds),
                lease = req.leaseSec?.let(Duration::ofSeconds),
            )
        if (claimed == null) {
            call.respond(HttpStatusCode.NoContent)
        } else {
            call.respondJson(DispatchClaimedResponse(claimed.toSchema()))
        }
    }

    post("/api/dispatches/{id}/heartbeat", {
        tags = listOf("dispatches")
        summary = "Extend a claimed dispatch lease"
        request {
            pathParameter<Long>("id") { description = "Dispatch id." }
            body<DispatchHeartbeatRequest> { mediaTypes(ContentType.Application.Json) }
        }
        response {
            code(HttpStatusCode.OK) { body<DispatchLeaseResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.Conflict) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        if (!call.requireCompanionAuth(dispatchConfig)) return@post
        val id = call.dispatchId() ?: return@post
        val req = call.decodeBody<DispatchHeartbeatRequest>() ?: return@post
        if (req.leaseToken.isBlank()) return@post call.respondError("invalid_lease_token", HttpStatusCode.BadRequest)
        when (val result = dispatches.heartbeat(id, req.leaseToken, req.leaseSec?.let(Duration::ofSeconds))) {
            is DispatchLeaseResult.Updated ->
                call.respondJson(
                    DispatchLeaseResponse(
                        id = result.id,
                        leaseExpiresAt = result.leaseExpiresAt.toString(),
                    ),
                )
            DispatchLeaseResult.InvalidLease -> call.respondError("invalid_lease", HttpStatusCode.Conflict)
            DispatchLeaseResult.NotFound -> call.respondError("not_found", HttpStatusCode.NotFound)
        }
    }

    post("/api/dispatches/{id}/complete", {
        tags = listOf("dispatches")
        summary = "Complete a claimed dispatch"
        request {
            pathParameter<Long>("id") { description = "Dispatch id." }
            body<DispatchCompleteRequest> { mediaTypes(ContentType.Application.Json) }
        }
        response {
            code(HttpStatusCode.OK) { body<DispatchMutationResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.Conflict) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        if (!call.requireCompanionAuth(dispatchConfig)) return@post
        val id = call.dispatchId() ?: return@post
        val req = call.decodeBody<DispatchCompleteRequest>() ?: return@post
        if (req.leaseToken.isBlank()) return@post call.respondError("invalid_lease_token", HttpStatusCode.BadRequest)
        val request = dispatchCompleteRequest(req)
        when (val result = dispatches.complete(id, req.leaseToken, request)) {
            is DispatchCompleteOutcome.Completed ->
                call.respondJson(DispatchMutationResponse(id = result.id, status = STATUS_COMPLETED, watchDone = result.watchDone))
            DispatchCompleteOutcome.InvalidLease -> call.respondError("invalid_lease", HttpStatusCode.Conflict)
            DispatchCompleteOutcome.NotFound -> call.respondError("not_found", HttpStatusCode.NotFound)
        }
    }

    post("/api/dispatches/{id}/fail", {
        tags = listOf("dispatches")
        summary = "Fail a claimed dispatch"
        request {
            pathParameter<Long>("id") { description = "Dispatch id." }
            body<DispatchFailRequest> { mediaTypes(ContentType.Application.Json) }
        }
        response {
            code(HttpStatusCode.OK) { body<DispatchMutationResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.Conflict) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        if (!call.requireCompanionAuth(dispatchConfig)) return@post
        val id = call.dispatchId() ?: return@post
        val req = call.decodeBody<DispatchFailRequest>() ?: return@post
        if (req.leaseToken.isBlank()) return@post call.respondError("invalid_lease_token", HttpStatusCode.BadRequest)
        if (req.error.isBlank()) return@post call.respondError("invalid_error", HttpStatusCode.BadRequest)
        val request = dispatchFailRequest(req)
        when (val result = dispatches.fail(id, req.leaseToken, request)) {
            is DispatchFailResult.Failed -> call.respondJson(DispatchMutationResponse(id = result.dispatch.id, status = STATUS_FAILED))
            DispatchFailResult.InvalidLease -> call.respondError("invalid_lease", HttpStatusCode.Conflict)
            DispatchFailResult.NotFound -> call.respondError("not_found", HttpStatusCode.NotFound)
        }
    }

    post("/api/dispatches/test", {
        tags = listOf("dispatches")
        summary = "Queue a synthetic companion dispatch"
        request { body<DispatchTestEventRequest> { mediaTypes(ContentType.Application.Json) } }
        response {
            code(HttpStatusCode.Created) { body<DispatchQueuedResponse> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        if (!call.requireCompanionAuth(dispatchConfig)) return@post
        if (!dispatchConfig.testEndpointEnabled) {
            return@post call.respondError("test_dispatch_disabled", HttpStatusCode.Forbidden)
        }
        val req = call.decodeBody<DispatchTestEventRequest>() ?: return@post
        val error = validateTestEvent(req)
        if (error != null) return@post call.respondError(error.first, HttpStatusCode.BadRequest, error.second)
        val queued =
            testEvents.enqueue(
                DispatchTestEventInput(
                    kind = req.kind,
                    vendor = req.vendor,
                    simulateResult = req.simulateResult,
                    payloadVersion = req.payloadVersion,
                    payload = req.payload,
                    watchId = req.watchId,
                    stopWhenTriggered = req.stopWhenTriggered,
                ),
            )
        call.respondJson(DispatchQueuedResponse(queued.toSchema()), HttpStatusCode.Created)
    }
}

private fun DispatchClaimRequest.claimKinds(): List<String> = kinds.takeIf { it.isNotEmpty() } ?: listOf(kind ?: DEFAULT_DISPATCH_KIND)

private suspend inline fun <reified T> ApplicationCall.decodeBody(): T? {
    val raw = receiveText()
    return try {
        dispatchJson.decodeFromString<T>(raw)
    } catch (e: Exception) {
        respondError("invalid_body", HttpStatusCode.BadRequest, e.message)
        null
    }
}

private suspend fun ApplicationCall.dispatchId(): Long? =
    parameters["id"]?.toLongOrNull()
        ?: run {
            respondError("invalid_id", HttpStatusCode.BadRequest)
            null
        }

private fun validateTestEvent(req: DispatchTestEventRequest): Pair<String, String?>? {
    val kind = req.kind?.let(::normalizeDispatchKey)
    if (kind?.isBlank() == true) return "invalid_kind" to "kind must be non-blank"
    if (kind != null && kind != TEST_DISPATCH_KIND) return "invalid_kind" to "test endpoint only queues test dispatches"
    if (req.vendor.isBlank()) return "invalid_vendor" to "vendor must be non-blank"
    if (req.simulateResult.trim().lowercase() !in validSimulateResults) {
        return "invalid_simulate_result" to "simulate_result must be success or failure"
    }
    if (req.payloadVersion?.isBlank() == true) return "invalid_payload_version" to "payload_version must be non-blank"
    return null
}

private fun dispatchCompleteRequest(req: DispatchCompleteRequest): JsonObject = dispatchJson.encodeToJsonElement(req).jsonObject

private fun dispatchFailRequest(req: DispatchFailRequest): JsonObject = dispatchJson.encodeToJsonElement(req).jsonObject

private fun DispatchClaimed.toSchema(): DispatchClaimedSchema =
    DispatchClaimedSchema(
        id = id,
        kind = kind,
        vendor = vendor,
        payloadVersion = payloadVersion,
        payload = payload,
        leaseToken = leaseToken,
        leaseExpiresAt = leaseExpiresAt.toString(),
        expiresAt = expiresAt.toString(),
    )

private fun DispatchQueued.toSchema(): DispatchQueuedSchema =
    DispatchQueuedSchema(
        id = id,
        kind = kind,
        vendor = vendor,
        payloadVersion = payloadVersion,
        expiresAt = expiresAt.toString(),
        notifiedWaiters = notifiedWaiters,
    )

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(dispatchJson.encodeToString(body), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondText(dispatchJson.encodeToString(ApiErrorSchema(error = error, detail = detail)), ContentType.Application.Json, status)
}
