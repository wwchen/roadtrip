package ca.floo.roadtrip.route.internal

import ca.floo.roadtrip.model.api.BookingRelayActionDto
import ca.floo.roadtrip.model.api.BookingRelayActionOutcome
import ca.floo.roadtrip.model.api.BookingRelayFailureCategory
import ca.floo.roadtrip.model.api.BookingRelayFailureDto
import ca.floo.roadtrip.model.api.BookingRelayHealthDto
import ca.floo.roadtrip.model.api.BookingRelayHoldDto
import ca.floo.roadtrip.model.api.BookingRelayHoldOutcome
import ca.floo.roadtrip.model.api.BookingRelayLoginDto
import ca.floo.roadtrip.model.api.BookingRelayLoginOutcome
import ca.floo.roadtrip.model.api.BookingRelaySession
import ca.floo.roadtrip.model.api.BookingRelaySignOutDto
import ca.floo.roadtrip.model.api.BookingRelaySignOutOutcome
import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.service.booking.ProfileHoldOutcome
import ca.floo.roadtrip.service.booking.ProfileWipe
import ca.floo.roadtrip.service.booking.RECGOV_CART_URL
import ca.floo.roadtrip.service.booking.RelayAnswer
import ca.floo.roadtrip.service.booking.recGovFailureCategory
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.RecGovSessionCodes

internal const val RELAY_TIMEOUT_CODE = "relay_timeout"
private const val RELAY_TIMEOUT_DETAIL = "the hold may still finish; check the cart"

private val timeoutFailure =
    BookingRelayFailureDto(RELAY_TIMEOUT_CODE, BookingRelayFailureCategory.RETRY_LATER, RELAY_TIMEOUT_DETAIL)

private fun BookingFailureCategory.toWire(): BookingRelayFailureCategory =
    when (this) {
        BookingFailureCategory.CALLER_ACTION -> BookingRelayFailureCategory.CALLER_ACTION
        BookingFailureCategory.RETRY_LATER -> BookingRelayFailureCategory.RETRY_LATER
        BookingFailureCategory.UPSTREAM -> BookingRelayFailureCategory.UPSTREAM
    }

private fun failureOf(
    code: String,
    detail: String?,
): BookingRelayFailureDto = BookingRelayFailureDto(code, recGovFailureCategory(code).toWire(), detail)

internal fun RelayAnswer<ProfileHoldOutcome>.toHoldDto(): BookingRelayHoldDto =
    when (this) {
        is RelayAnswer.Answered ->
            when (val outcome = value) {
                is ProfileHoldOutcome.Held -> BookingRelayHoldDto(BookingRelayHoldOutcome.HELD, cartUrl = outcome.cartUrl)
                is ProfileHoldOutcome.Blocked ->
                    BookingRelayHoldDto(
                        BookingRelayHoldOutcome.BLOCKED,
                        failure = BookingRelayFailureDto(outcome.code, outcome.category.toWire(), outcome.detail),
                    )
            }
        RelayAnswer.TimedOut ->
            BookingRelayHoldDto(BookingRelayHoldOutcome.TIMED_OUT, cartUrl = RECGOV_CART_URL, failure = timeoutFailure)
    }

internal fun RelayAnswer<CompanionLoginResult>.toLoginDto(): BookingRelayLoginDto =
    when (this) {
        is RelayAnswer.Answered ->
            when (val result = value) {
                CompanionLoginResult.Ok -> BookingRelayLoginDto(BookingRelayLoginOutcome.SIGNED_IN)
                is CompanionLoginResult.MfaRequired ->
                    BookingRelayLoginDto(
                        BookingRelayLoginOutcome.MFA_REQUIRED,
                        challengeId = result.challengeId,
                        expiresAt = result.expiresAt,
                    )
                is CompanionLoginResult.Failed ->
                    BookingRelayLoginDto(BookingRelayLoginOutcome.FAILED, failure = failureOf(result.code, result.detail))
            }
        RelayAnswer.TimedOut -> BookingRelayLoginDto(BookingRelayLoginOutcome.TIMED_OUT, failure = timeoutFailure)
    }

internal fun RelayAnswer<CompanionActionResult>.toActionDto(): BookingRelayActionDto =
    when (this) {
        is RelayAnswer.Answered ->
            when (val result = value) {
                CompanionActionResult.Ok -> BookingRelayActionDto(BookingRelayActionOutcome.OK)
                is CompanionActionResult.Failed ->
                    BookingRelayActionDto(BookingRelayActionOutcome.FAILED, failureOf(result.code, result.detail))
            }
        RelayAnswer.TimedOut -> BookingRelayActionDto(BookingRelayActionOutcome.TIMED_OUT, timeoutFailure)
    }

internal fun RelayAnswer<CompanionSessionHealth>.toHealthDto(): BookingRelayHealthDto =
    when (this) {
        is RelayAnswer.Answered ->
            when (val health = value) {
                CompanionSessionHealth.Active -> BookingRelayHealthDto(BookingRelaySession.ACTIVE)
                CompanionSessionHealth.NeverLoggedIn -> BookingRelayHealthDto(BookingRelaySession.NEVER_LOGGED_IN)
                is CompanionSessionHealth.Inactive -> BookingRelayHealthDto(BookingRelaySession.INACTIVE, code = health.code)
                is CompanionSessionHealth.CheckFailed -> BookingRelayHealthDto(BookingRelaySession.CHECK_FAILED, code = health.code)
                is CompanionSessionHealth.Unavailable ->
                    BookingRelayHealthDto(
                        BookingRelaySession.UNAVAILABLE,
                        code = RecGovSessionCodes.COMPANION_UNAVAILABLE,
                        detail = health.detail,
                    )
            }
        RelayAnswer.TimedOut -> BookingRelayHealthDto(BookingRelaySession.UNAVAILABLE, code = RELAY_TIMEOUT_CODE)
    }

internal fun RelayAnswer<ProfileWipe>.toSignOutDto(): BookingRelaySignOutDto =
    when (this) {
        is RelayAnswer.Answered ->
            when (val destroyed = value.destroyed) {
                CompanionActionResult.Ok ->
                    BookingRelaySignOutDto(BookingRelaySignOutOutcome.WIPED, signedOut = value.signedOut, profileDestroyed = true)
                is CompanionActionResult.Failed ->
                    BookingRelaySignOutDto(
                        BookingRelaySignOutOutcome.FAILED,
                        signedOut = value.signedOut,
                        profileDestroyed = false,
                        failure = failureOf(destroyed.code, destroyed.detail),
                    )
            }
        RelayAnswer.TimedOut ->
            BookingRelaySignOutDto(
                BookingRelaySignOutOutcome.TIMED_OUT,
                signedOut = false,
                profileDestroyed = false,
                failure = timeoutFailure,
            )
    }
