package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import ca.floo.roadtrip.support.runCatchingCancellable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

private const val ERROR_COMPANION_EXCEPTION = "companion_exception"
private const val FIELD_PROFILE_ID = "profile_id"
private const val FIELD_START_DATE = "start_date"
private const val FIELD_END_DATE = "end_date"
private const val FIELD_CAMPSITE_ID = "campsite_id"

internal interface RecGovHoldSession {
    suspend fun health(): CompanionSessionHealth

    suspend fun refresh(): CompanionActionResult
}

internal fun CompanionSessionPort.holdSession(profileId: String): RecGovHoldSession =
    object : RecGovHoldSession {
        override suspend fun health(): CompanionSessionHealth = this@holdSession.health(profileId)

        override suspend fun refresh(): CompanionActionResult = this@holdSession.refresh(profileId, unattended = true)
    }

internal data class ProfileHoldRequest(
    val profileId: String,
    val vendorSiteId: String,
    val arrival: LocalDate,
    val checkout: LocalDate,
) {
    fun toAtcPayload(): JsonObject =
        buildJsonObject {
            put(FIELD_PROFILE_ID, profileId)
            put(FIELD_START_DATE, arrival.toString())
            put(FIELD_END_DATE, checkout.toString())
            put(FIELD_CAMPSITE_ID, vendorSiteId)
        }
}

internal data class ProfileHoldBlocker(
    val code: String,
    val detail: String?,
)

internal sealed interface ProfileHoldOutcome {
    data class Held(
        val cartUrl: String,
        val response: JsonObject,
    ) : ProfileHoldOutcome

    data class Blocked(
        val code: String,
        val category: BookingFailureCategory,
        val detail: String,
        val response: JsonObject?,
    ) : ProfileHoldOutcome
}

private val failureCategories: Map<String, BookingFailureCategory> =
    buildMap {
        listOf(
            RecGovSessionCodes.SESSION_EXPIRED,
            RecGovSessionCodes.SESSION_LAPSED,
            RecGovSessionCodes.SPA_LOGGED_OUT,
            RecGovSessionCodes.REFRESH_FAILED,
            RecGovSessionCodes.COMPANION_LOGIN_FAILED,
            RecGovSessionCodes.LOGIN_FAILED,
            RecGovSessionCodes.NOT_AUTHENTICATED,
            RecGovSessionCodes.NOT_CONFIGURED,
            RecGovSessionCodes.MFA_REQUIRED,
        ).forEach { put(it, BookingFailureCategory.CALLER_ACTION) }

        listOf(
            RecGovSessionCodes.PROFILE_BUSY,
            RecGovSessionCodes.BROWSER_CAP_REACHED,
            RecGovSessionCodes.LOGIN_BACKOFF,
            RecGovSessionCodes.CAPTCHA_REQUIRED,
            BookingActionCodes.CART_NOT_ADDED,
            RecGovBookingCodes.CONFIRMATION_DISABLED,
            RecGovBookingCodes.DATES_NOT_OFFERED,
            RecGovBookingCodes.NO_RESERVE_BUTTON,
        ).forEach { put(it, BookingFailureCategory.RETRY_LATER) }
    }

internal fun recGovFailureCategory(code: String): BookingFailureCategory = failureCategories[code] ?: BookingFailureCategory.UPSTREAM

private fun undetailed(category: BookingFailureCategory): String =
    when (category) {
        BookingFailureCategory.CALLER_ACTION -> "this needs your attention in Settings"
        BookingFailureCategory.RETRY_LATER -> "the hold could not be made this time"
        BookingFailureCategory.UPSTREAM -> COMPANION_ERROR_DETAIL
    }

internal class RecGovProfileHold(
    private val executor: RecGovAtcExecutor,
) {
    suspend fun hold(
        session: RecGovHoldSession?,
        request: ProfileHoldRequest,
        relogin: (suspend (healthCode: String?) -> ProfileHoldBlocker?)? = null,
    ): ProfileHoldOutcome {
        preflight(session, relogin)?.let { return blocked(it.code, it.detail, response = null) }
        return when (
            val outcome =
                runCatchingCancellable { executor.addToCart(request.toAtcPayload()) }
                    .getOrElse { RecGovAtcOutcome.Failed(error = ERROR_COMPANION_EXCEPTION, detail = it.message) }
        ) {
            is RecGovAtcOutcome.Completed -> ProfileHoldOutcome.Held(RECGOV_CART_URL, outcome.response)
            is RecGovAtcOutcome.Failed -> blocked(outcome.error, outcome.detail, outcome.response)
        }
    }

    private suspend fun preflight(
        session: RecGovHoldSession?,
        relogin: (suspend (String?) -> ProfileHoldBlocker?)?,
    ): ProfileHoldBlocker? {
        val client = session ?: return null
        return when (val health = client.health()) {
            is CompanionSessionHealth.Active -> null
            is CompanionSessionHealth.Unavailable -> ProfileHoldBlocker(RecGovSessionCodes.COMPANION_UNAVAILABLE, health.detail)
            is CompanionSessionHealth.CheckFailed ->
                ProfileHoldBlocker(health.code ?: RecGovSessionCodes.AUTH_CHECK_EXCEPTION, COMPANION_ERROR_DETAIL)
            is CompanionSessionHealth.NeverLoggedIn -> recover(client, null, relogin)
            is CompanionSessionHealth.Inactive -> recover(client, health.code, relogin)
        }
    }

    private suspend fun recover(
        client: RecGovHoldSession,
        healthCode: String?,
        relogin: (suspend (String?) -> ProfileHoldBlocker?)?,
    ): ProfileHoldBlocker? {
        if (client.refresh() == CompanionActionResult.Ok) return null
        val credentialLogin = relogin ?: return sessionExpired()
        return credentialLogin(healthCode)
    }

    private fun sessionExpired() = ProfileHoldBlocker(RECGOV_SESSION_EXPIRED_ERROR, RECGOV_SESSION_EXPIRED_DETAIL)

    private fun blocked(
        code: String,
        detail: String?,
        response: JsonObject?,
    ): ProfileHoldOutcome.Blocked {
        val category = recGovFailureCategory(code)
        return ProfileHoldOutcome.Blocked(code, category, detail ?: undetailed(category), response)
    }
}
