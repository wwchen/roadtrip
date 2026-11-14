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
enum class BookingRelayFailureCategory {
    @SerialName("caller_action")
    CALLER_ACTION,

    @SerialName("retry_later")
    RETRY_LATER,

    @SerialName("upstream")
    UPSTREAM,
}

@Serializable
data class BookingRelayFailureDto(
    val code: String,
    val category: BookingRelayFailureCategory,
    val detail: String? = null,
)

@Serializable
enum class BookingRelayHoldOutcome {
    @SerialName("held")
    HELD,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("timed_out")
    TIMED_OUT,
}

@Serializable
data class BookingRelayHoldDto(
    val outcome: BookingRelayHoldOutcome,
    @SerialName("cart_url") val cartUrl: String? = null,
    val failure: BookingRelayFailureDto? = null,
)

@Serializable
enum class BookingRelayLoginOutcome {
    @SerialName("signed_in")
    SIGNED_IN,

    @SerialName("mfa_required")
    MFA_REQUIRED,

    @SerialName("failed")
    FAILED,

    @SerialName("timed_out")
    TIMED_OUT,
}

@Serializable
data class BookingRelayLoginDto(
    val outcome: BookingRelayLoginOutcome,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val failure: BookingRelayFailureDto? = null,
)

@Serializable
enum class BookingRelayActionOutcome {
    @SerialName("ok")
    OK,

    @SerialName("failed")
    FAILED,

    @SerialName("timed_out")
    TIMED_OUT,
}

@Serializable
data class BookingRelayActionDto(
    val outcome: BookingRelayActionOutcome,
    val failure: BookingRelayFailureDto? = null,
)

@Serializable
enum class BookingRelaySession {
    @SerialName("active")
    ACTIVE,

    @SerialName("never_logged_in")
    NEVER_LOGGED_IN,

    @SerialName("inactive")
    INACTIVE,

    @SerialName("check_failed")
    CHECK_FAILED,

    @SerialName("unavailable")
    UNAVAILABLE,
}

@Serializable
data class BookingRelayHealthDto(
    val session: BookingRelaySession,
    val code: String? = null,
    val detail: String? = null,
)

@Serializable
enum class BookingRelaySignOutOutcome {
    @SerialName("wiped")
    WIPED,

    @SerialName("failed")
    FAILED,

    @SerialName("timed_out")
    TIMED_OUT,
}

@Serializable
data class BookingRelaySignOutDto(
    val outcome: BookingRelaySignOutOutcome,
    @SerialName("signed_out") val signedOut: Boolean,
    @SerialName("profile_destroyed") val profileDestroyed: Boolean,
    val failure: BookingRelayFailureDto? = null,
)
