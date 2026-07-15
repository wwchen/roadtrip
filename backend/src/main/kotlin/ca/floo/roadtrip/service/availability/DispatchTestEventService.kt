package ca.floo.roadtrip.service.availability

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SYNTHETIC_DISPATCH_KIND = "test"

internal data class DispatchTestEventInput(
    val kind: String?,
    val vendor: String,
    val simulateResult: String,
    val payloadVersion: String?,
    val payload: JsonObject,
    val watchId: Long?,
    val stopWhenTriggered: Boolean,
)

internal class DispatchTestEventService(
    private val dispatches: DispatchEnqueuer,
) {
    suspend fun enqueue(input: DispatchTestEventInput): DispatchQueued {
        val normalizedKind = input.kind?.let(::normalizeDispatchKey) ?: SYNTHETIC_DISPATCH_KIND
        val normalizedVendor = normalizeDispatchKey(input.vendor)
        val version =
            input.payloadVersion?.let(::normalizeDispatchKey)
                ?: dispatchPayloadVersion(normalizedKind, normalizedVendor)
        return dispatches.enqueue(
            DispatchCreateInput(
                kind = normalizedKind,
                vendor = normalizedVendor,
                payloadVersion = version,
                payload = payload(input, normalizedVendor, version),
                watchId = input.watchId,
                stopWhenTriggered = input.stopWhenTriggered,
            ),
        )
    }

    private fun payload(
        input: DispatchTestEventInput,
        vendor: String,
        payloadVersion: String,
    ): JsonObject =
        buildJsonObject {
            input.payload.forEach { (key, value) -> put(key, value) }
            put("vendor", vendor)
            put("payload_version", payloadVersion)
            input.watchId?.let { put("watch_id", it) }
            put("simulate_result", normalizeDispatchKey(input.simulateResult))
        }
}
