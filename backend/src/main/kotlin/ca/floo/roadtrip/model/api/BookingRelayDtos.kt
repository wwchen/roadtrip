package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BookingRelayProfileRequest(
    @SerialName("profile_id") val profileId: String,
)

@Serializable
data class BookingRelayLoginRequest(
    @SerialName("profile_id") val profileId: String,
    val username: String,
    val password: String,
)

@Serializable
data class BookingRelayMfaRequest(
    @SerialName("profile_id") val profileId: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("mfa_code") val mfaCode: String,
)

@Serializable
data class BookingRelayAtcRequest(
    @SerialName("profile_id") val profileId: String,
    @SerialName("vendor_site_id") val vendorSiteId: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
)

@Serializable
data class BookingRelayResponseDto(
    val ok: Boolean,
    val code: String? = null,
    val category: String? = null,
    val detail: String? = null,
    @SerialName("cart_url") val cartUrl: String? = null,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val health: String? = null,
    @SerialName("signed_out") val signedOut: Boolean? = null,
    @SerialName("profile_destroyed") val profileDestroyed: Boolean? = null,
)
