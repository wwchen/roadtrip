package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorSchema(
    val error: String,
    val detail: String? = null,
    /** The booking adapter that refused, where one was reached. Absent otherwise. */
    val provider: String? = null,
    /** That adapter's booking site as a person reads it. Absent with [provider]. */
    @SerialName("provider_display") val providerDisplay: String? = null,
)
