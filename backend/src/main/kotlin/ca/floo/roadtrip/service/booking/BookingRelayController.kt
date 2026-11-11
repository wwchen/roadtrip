package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.api.BookingRelayAtcRequest
import ca.floo.roadtrip.model.api.BookingRelayLoginRequest
import ca.floo.roadtrip.model.api.BookingRelayMfaRequest
import ca.floo.roadtrip.model.api.BookingRelayResponseDto
import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate

internal const val RELAY_TIMEOUT_CODE = "relay_timeout"
private const val RELAY_TIMEOUT_DETAIL = "the hold may still finish; check the cart"

private const val HEALTH_ACTIVE = "active"
private const val HEALTH_NEVER_LOGGED_IN = "never_logged_in"
private const val HEALTH_INACTIVE = "inactive"
private const val HEALTH_CHECK_FAILED = "check_failed"
private const val HEALTH_UNAVAILABLE = "unavailable"

internal sealed interface RelayAnswer {
    data class Answered(
        val body: BookingRelayResponseDto,
    ) : RelayAnswer

    data class TimedOut(
        val body: BookingRelayResponseDto,
    ) : RelayAnswer
}

internal class BookingRelayController(
    private val companion: CompanionSessionPort,
    atc: RecGovAtcExecutor,
    private val timeout: Duration,
) {
    private val hold = RecGovProfileHold(atc)

    suspend fun health(profileId: String): RelayAnswer = bounded { healthAnswer(companion.health(profileId)) }

    suspend fun refresh(profileId: String): RelayAnswer = bounded { actionAnswer(companion.refresh(profileId)) }

    suspend fun verify(profileId: String): RelayAnswer = bounded { actionAnswer(companion.verify(profileId)) }

    suspend fun login(request: BookingRelayLoginRequest): RelayAnswer =
        bounded { loginAnswer(companion.login(request.profileId, request.username, request.password)) }

    suspend fun completeMfa(request: BookingRelayMfaRequest): RelayAnswer =
        bounded { loginAnswer(companion.completeMfa(request.profileId, request.challengeId, request.mfaCode)) }

    suspend fun signout(profileId: String): RelayAnswer =
        bounded {
            val signedOut = companion.logout(profileId) == CompanionActionResult.Ok
            when (val destroyed = companion.destroyProfile(profileId)) {
                CompanionActionResult.Ok -> BookingRelayResponseDto(ok = true, signedOut = signedOut, profileDestroyed = true)
                is CompanionActionResult.Failed ->
                    failure(destroyed.code, destroyed.detail).copy(signedOut = signedOut, profileDestroyed = false)
            }
        }

    suspend fun atc(
        request: BookingRelayAtcRequest,
        arrival: LocalDate,
        checkout: LocalDate,
    ): RelayAnswer =
        bounded(cartUrlOnTimeout = RECGOV_CART_URL) {
            val holdRequest = ProfileHoldRequest(request.profileId, request.vendorSiteId, arrival, checkout)
            when (val outcome = hold.hold(companion.holdSession(request.profileId), holdRequest)) {
                is ProfileHoldOutcome.Held -> BookingRelayResponseDto(ok = true, cartUrl = outcome.cartUrl)
                is ProfileHoldOutcome.Blocked ->
                    BookingRelayResponseDto(
                        ok = false,
                        code = outcome.code,
                        category = outcome.category.wire(),
                        detail = outcome.detail,
                    )
            }
        }

    private suspend fun bounded(
        cartUrlOnTimeout: String? = null,
        work: suspend () -> BookingRelayResponseDto,
    ): RelayAnswer =
        withTimeoutOrNull(timeout.toMillis()) { RelayAnswer.Answered(work()) }
            ?: RelayAnswer.TimedOut(
                BookingRelayResponseDto(
                    ok = false,
                    code = RELAY_TIMEOUT_CODE,
                    category = BookingFailureCategory.RETRY_LATER.wire(),
                    detail = RELAY_TIMEOUT_DETAIL,
                    cartUrl = cartUrlOnTimeout,
                ),
            )

    private fun healthAnswer(health: CompanionSessionHealth): BookingRelayResponseDto =
        when (health) {
            CompanionSessionHealth.Active -> BookingRelayResponseDto(ok = true, health = HEALTH_ACTIVE)
            CompanionSessionHealth.NeverLoggedIn -> BookingRelayResponseDto(ok = false, health = HEALTH_NEVER_LOGGED_IN)
            is CompanionSessionHealth.Inactive -> BookingRelayResponseDto(ok = false, health = HEALTH_INACTIVE, code = health.code)
            is CompanionSessionHealth.CheckFailed ->
                BookingRelayResponseDto(ok = false, health = HEALTH_CHECK_FAILED, code = health.code)
            is CompanionSessionHealth.Unavailable ->
                BookingRelayResponseDto(
                    ok = false,
                    health = HEALTH_UNAVAILABLE,
                    code = RecGovSessionCodes.COMPANION_UNAVAILABLE,
                    detail = health.detail,
                )
        }

    private fun actionAnswer(result: CompanionActionResult): BookingRelayResponseDto =
        when (result) {
            CompanionActionResult.Ok -> BookingRelayResponseDto(ok = true)
            is CompanionActionResult.Failed -> failure(result.code, result.detail)
        }

    private fun loginAnswer(result: CompanionLoginResult): BookingRelayResponseDto =
        when (result) {
            CompanionLoginResult.Ok -> BookingRelayResponseDto(ok = true)
            is CompanionLoginResult.MfaRequired ->
                failure(RecGovSessionCodes.MFA_REQUIRED, null).copy(challengeId = result.challengeId, expiresAt = result.expiresAt)
            is CompanionLoginResult.Failed -> failure(result.code, result.detail)
        }

    private fun failure(
        code: String,
        detail: String?,
    ): BookingRelayResponseDto =
        BookingRelayResponseDto(ok = false, code = code, category = recGovFailureCategory(code).wire(), detail = detail)
}

internal fun BookingFailureCategory.wire(): String = name.lowercase()
