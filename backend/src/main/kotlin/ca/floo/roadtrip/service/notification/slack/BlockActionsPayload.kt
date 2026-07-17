package ca.floo.roadtrip.service.notification.slack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class BlockActionsPayload(
    val type: String,
    val actions: List<BlockAction> = emptyList(),
    @SerialName("response_url")
    val responseUrl: String? = null,
)
