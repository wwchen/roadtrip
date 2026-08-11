package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    /** Null means unchanged, matching [theme] — a client updating one field must
     *  not silently clear the other. */
    @SerialName("display_name") val displayName: String? = null,
    /** One of [ca.floo.roadtrip.service.settings.THEME_VALUES]. Null means unchanged. */
    val theme: String? = null,
)
