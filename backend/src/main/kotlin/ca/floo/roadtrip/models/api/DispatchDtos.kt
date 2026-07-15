package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val DEFAULT_DISPATCH_KIND = "atc"
const val DEFAULT_DISPATCH_WAIT_SEC = 30L
const val DEFAULT_DISPATCH_LEASE_SEC = 30L

@Serializable
data class DispatchClaimRequest(
    val kind: String = DEFAULT_DISPATCH_KIND,
    val vendors: List<String>,
    @SerialName("payload_versions") val payloadVersions: List<String> = emptyList(),
    @SerialName("wait_sec") val waitSec: Long = DEFAULT_DISPATCH_WAIT_SEC,
    @SerialName("lease_sec") val leaseSec: Long = DEFAULT_DISPATCH_LEASE_SEC,
)

@Serializable
data class DispatchClaimedResponse(
    val dispatch: DispatchClaimedSchema,
)

@Serializable
data class DispatchClaimedSchema(
    val id: Long,
    val kind: String,
    val vendor: String,
    @SerialName("payload_version") val payloadVersion: String,
    val payload: JsonObject,
    @SerialName("lease_token") val leaseToken: String,
    @SerialName("lease_expires_at") val leaseExpiresAt: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class DispatchHeartbeatRequest(
    @SerialName("lease_token") val leaseToken: String,
    @SerialName("lease_sec") val leaseSec: Long = DEFAULT_DISPATCH_LEASE_SEC,
)

@Serializable
data class DispatchLeaseResponse(
    val id: Long,
    @SerialName("lease_expires_at") val leaseExpiresAt: String,
)

@Serializable
data class DispatchCompleteRequest(
    @SerialName("lease_token") val leaseToken: String,
    val result: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DispatchFailRequest(
    @SerialName("lease_token") val leaseToken: String,
    val error: String,
    val detail: String? = null,
    val result: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DispatchMutationResponse(
    val id: Long,
    val status: String,
    @SerialName("watch_done") val watchDone: Boolean? = null,
)

@Serializable
data class DispatchTestEventRequest(
    val vendor: String,
    @SerialName("simulate_result") val simulateResult: String,
    val kind: String? = null,
    @SerialName("payload_version") val payloadVersion: String? = null,
    @SerialName("watch_id") val watchId: Long? = null,
    @SerialName("stop_when_triggered") val stopWhenTriggered: Boolean = false,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class DispatchQueuedResponse(
    val dispatch: DispatchQueuedSchema,
)

@Serializable
data class DispatchQueuedSchema(
    val id: Long,
    val kind: String,
    val vendor: String,
    @SerialName("payload_version") val payloadVersion: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("notified_waiters") val notifiedWaiters: Int,
)
