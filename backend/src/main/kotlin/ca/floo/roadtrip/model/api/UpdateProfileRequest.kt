package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    @SerialName("display_name") val displayName: String?,
    /** One of [ca.floo.roadtrip.service.settings.THEME_VALUES]. Null means unchanged. */
    val theme: String? = null,
)
