package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.CompanionSessionPort
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.LocalDate

internal sealed interface RelayAnswer<out T> {
    data class Answered<T>(
        val value: T,
    ) : RelayAnswer<T>

    data object TimedOut : RelayAnswer<Nothing>
}

internal data class ProfileWipe(
    val signedOut: Boolean,
    val destroyed: CompanionActionResult,
)

internal class BookingRelayController(
    private val companion: CompanionSessionPort,
    atc: RecGovAtcExecutor,
    private val timeout: Duration,
) {
    private val hold = RecGovProfileHold(atc)

    suspend fun health(profileId: String): RelayAnswer<CompanionSessionHealth> = bounded { companion.health(profileId) }

    suspend fun refresh(profileId: String): RelayAnswer<CompanionActionResult> = bounded { companion.refresh(profileId) }

    suspend fun verify(profileId: String): RelayAnswer<CompanionActionResult> = bounded { companion.verify(profileId) }

    suspend fun login(
        profileId: String,
        username: String,
        password: String,
    ): RelayAnswer<CompanionLoginResult> = bounded { companion.login(profileId, username, password) }

    suspend fun completeMfa(
        profileId: String,
        challengeId: String,
        code: String,
    ): RelayAnswer<CompanionLoginResult> = bounded { companion.completeMfa(profileId, challengeId, code) }

    suspend fun signout(profileId: String): RelayAnswer<ProfileWipe> =
        bounded {
            val signedOut = companion.logout(profileId) == CompanionActionResult.Ok
            ProfileWipe(signedOut, companion.destroyProfile(profileId))
        }

    suspend fun atc(
        profileId: String,
        vendorSiteId: String,
        arrival: LocalDate,
        checkout: LocalDate,
    ): RelayAnswer<ProfileHoldOutcome> =
        bounded {
            hold.hold(companion.holdSession(profileId), ProfileHoldRequest(profileId, vendorSiteId, arrival, checkout))
        }

    private suspend fun <T> bounded(work: suspend () -> T): RelayAnswer<T> =
        withTimeoutOrNull(timeout.toMillis()) { RelayAnswer.Answered(work()) } ?: RelayAnswer.TimedOut
}
