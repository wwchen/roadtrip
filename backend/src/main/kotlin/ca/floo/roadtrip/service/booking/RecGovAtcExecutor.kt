package ca.floo.roadtrip.service.booking

import kotlinx.serialization.json.JsonObject

internal fun interface RecGovAtcExecutor {
    suspend fun addToCart(payload: JsonObject): RecGovAtcOutcome
}

internal sealed interface RecGovAtcOutcome {
    data class Completed(
        val response: JsonObject,
    ) : RecGovAtcOutcome

    data class Failed(
        val error: String,
        val detail: String?,
        val response: JsonObject? = null,
    ) : RecGovAtcOutcome
}
