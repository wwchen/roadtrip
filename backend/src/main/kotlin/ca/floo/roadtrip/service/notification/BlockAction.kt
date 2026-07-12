package ca.floo.roadtrip.service.notification

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class BlockAction(
    @SerialName("action_id")
    val actionId: String,
    val value: String? = null,
)
