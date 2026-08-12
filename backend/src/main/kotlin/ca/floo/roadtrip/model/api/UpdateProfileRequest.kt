package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    // Null means unchanged, for both fields.
    @SerialName("display_name") val displayName: String? = null,
    val theme: String? = null,
)
