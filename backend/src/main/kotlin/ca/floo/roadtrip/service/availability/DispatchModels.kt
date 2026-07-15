package ca.floo.roadtrip.service.availability

import kotlinx.serialization.json.JsonObject
import java.time.Instant

private const val BLANK_KEY_MESSAGE = "dispatch selector keys must be non-blank"
private const val EMPTY_VENDOR_MESSAGE = "dispatch selector vendors must be non-empty"
private const val PAYLOAD_VERSION_SUFFIX = "v1"

internal data class DispatchClaimSelector(
    val kind: String,
    val vendors: Set<String>,
    val payloadVersions: Set<String> = emptySet(),
) {
    init {
        require(kind.isNotBlank()) { BLANK_KEY_MESSAGE }
        require(vendors.isNotEmpty()) { EMPTY_VENDOR_MESSAGE }
        require(vendors.none { it.isBlank() }) { BLANK_KEY_MESSAGE }
        require(payloadVersions.none { it.isBlank() }) { BLANK_KEY_MESSAGE }
    }

    fun matches(
        kind: String,
        vendor: String,
        payloadVersion: String,
    ): Boolean =
        this.kind == normalizeDispatchKey(kind) &&
            vendors.contains(normalizeDispatchKey(vendor)) &&
            (
                payloadVersions.isEmpty() ||
                    payloadVersions.contains(normalizeDispatchKey(payloadVersion))
            )

    companion object {
        fun of(
            kind: String,
            vendors: Iterable<String>,
            payloadVersions: Iterable<String> = emptyList(),
        ): DispatchClaimSelector =
            DispatchClaimSelector(
                kind = normalizeDispatchKey(kind),
                vendors = normalizeDispatchKeys(vendors),
                payloadVersions = normalizeDispatchKeys(payloadVersions),
            )
    }
}

internal data class DispatchCreateInput(
    val kind: String,
    val vendor: String,
    val payloadVersion: String,
    val payload: JsonObject,
    val watchId: Long?,
    val stopWhenTriggered: Boolean,
)

internal fun interface DispatchEnqueuer {
    suspend fun enqueue(input: DispatchCreateInput): DispatchQueued
}

internal data class DispatchQueued(
    val id: Long,
    val kind: String,
    val vendor: String,
    val payloadVersion: String,
    val expiresAt: Instant,
    val notifiedWaiters: Int,
)

internal data class DispatchClaimed(
    val id: Long,
    val kind: String,
    val vendor: String,
    val payloadVersion: String,
    val payload: JsonObject,
    val leaseToken: String,
    val leaseExpiresAt: Instant,
    val expiresAt: Instant,
)

internal data class DispatchCompleted(
    val id: Long,
    val kind: String,
    val vendor: String,
    val payloadVersion: String,
    val payload: JsonObject,
    val watchId: Long?,
    val stopWhenTriggered: Boolean,
)

internal data class DispatchFailed(
    val id: Long,
    val kind: String,
    val vendor: String,
    val payloadVersion: String,
    val payload: JsonObject,
)

internal sealed class DispatchLeaseResult {
    data class Updated(
        val id: Long,
        val leaseExpiresAt: Instant,
    ) : DispatchLeaseResult()

    data object NotFound : DispatchLeaseResult()

    data object InvalidLease : DispatchLeaseResult()
}

internal sealed class DispatchCompleteResult {
    data class Completed(
        val dispatch: DispatchCompleted,
    ) : DispatchCompleteResult()

    data object NotFound : DispatchCompleteResult()

    data object InvalidLease : DispatchCompleteResult()
}

internal sealed class DispatchFailResult {
    data class Failed(
        val dispatch: DispatchFailed,
    ) : DispatchFailResult()

    data object NotFound : DispatchFailResult()

    data object InvalidLease : DispatchFailResult()
}

internal fun normalizeDispatchKey(value: String): String = value.trim().lowercase()

internal fun dispatchPayloadVersion(
    kind: String,
    vendor: String,
): String = "${normalizeDispatchKey(kind)}.${normalizeDispatchKey(vendor)}.$PAYLOAD_VERSION_SUFFIX"

private fun normalizeDispatchKeys(values: Iterable<String>): Set<String> =
    values.map(::normalizeDispatchKey).filter { it.isNotBlank() }.toSet()
