package ca.floo.roadtrip.route.internal

import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.route.common.encodeApiJson
import ca.floo.roadtrip.service.booking.ProfileHoldOutcome
import ca.floo.roadtrip.service.booking.ProfileWipe
import ca.floo.roadtrip.service.booking.RelayAnswer
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionLoginResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val FIXTURE_RESOURCE = "/booking-relay-answers.json"
private const val FIELD_OPERATION = "operation"
private const val FIELD_STATUS = "status"
private const val FIELD_BODY = "body"
private const val CART_URL = "https://www.recreation.gov/cart/held"
private const val CHALLENGE_ID = "challenge-1"
private const val EXPIRES_AT = "2026-12-10T10:00:00Z"
private const val DETAIL = "what the companion said"

private val prettyJson = Json { prettyPrint = true }

private fun case(
    operation: String,
    answer: RelayAnswer<*>,
    body: String,
): JsonObject =
    buildJsonObject {
        put(FIELD_OPERATION, operation)
        put(FIELD_STATUS, answer.relayStatus().value)
        put(FIELD_BODY, Json.parseToJsonElement(body))
    }

private fun hold(answer: RelayAnswer<ProfileHoldOutcome>) = case("hold", answer, encodeApiJson(answer.toHoldDto()))

private fun login(
    operation: String,
    answer: RelayAnswer<CompanionLoginResult>,
) = case(operation, answer, encodeApiJson(answer.toLoginDto()))

private fun action(
    operation: String,
    answer: RelayAnswer<CompanionActionResult>,
) = case(operation, answer, encodeApiJson(answer.toActionDto()))

private fun health(answer: RelayAnswer<CompanionSessionHealth>) = case("health", answer, encodeApiJson(answer.toHealthDto()))

private fun signOut(answer: RelayAnswer<ProfileWipe>) = case("sign_out", answer, encodeApiJson(answer.toSignOutDto()))

private fun <T> answered(value: T): RelayAnswer<T> = RelayAnswer.Answered(value)

private val cases: Map<String, JsonObject> =
    linkedMapOf(
        "hold_held" to hold(answered(ProfileHoldOutcome.Held(CART_URL, JsonObject(emptyMap())))),
        "hold_blocked" to
            hold(answered(ProfileHoldOutcome.Blocked(RecGovSessionCodes.PROFILE_BUSY, BookingFailureCategory.RETRY_LATER, DETAIL, null))),
        "hold_timed_out" to hold(RelayAnswer.TimedOut),
        "sign_in_signed_in" to login("sign_in", answered(CompanionLoginResult.Ok)),
        "sign_in_mfa_required" to login("sign_in", answered(CompanionLoginResult.MfaRequired(CHALLENGE_ID, EXPIRES_AT))),
        "sign_in_rejected" to login("sign_in", answered(CompanionLoginResult.Failed(RecGovSessionCodes.LOGIN_FAILED, DETAIL))),
        "sign_in_page_changed" to login("sign_in", answered(CompanionLoginResult.Failed(RecGovSessionCodes.LOGIN_PAGE_CHANGED))),
        "sign_in_timed_out" to login("sign_in", RelayAnswer.TimedOut),
        "submit_mfa_signed_in" to login("submit_mfa", answered(CompanionLoginResult.Ok)),
        "submit_mfa_invalid" to login("submit_mfa", answered(CompanionLoginResult.Failed(RecGovSessionCodes.MFA_INVALID))),
        "refresh_ok" to action("refresh", answered(CompanionActionResult.Ok)),
        "refresh_failed" to action("refresh", answered(CompanionActionResult.Failed(RecGovSessionCodes.REFRESH_FAILED, DETAIL))),
        "verify_not_authenticated" to action("verify", answered(CompanionActionResult.Failed(RecGovSessionCodes.NOT_AUTHENTICATED))),
        "verify_timed_out" to action("verify", RelayAnswer.TimedOut),
        "health_active" to health(answered(CompanionSessionHealth.Active)),
        "health_never_logged_in" to health(answered(CompanionSessionHealth.NeverLoggedIn)),
        "health_inactive" to health(answered(CompanionSessionHealth.Inactive(RecGovSessionCodes.SESSION_EXPIRED))),
        "health_check_failed" to health(answered(CompanionSessionHealth.CheckFailed(RecGovSessionCodes.AUTH_CHECK_EXCEPTION, DETAIL))),
        "health_unavailable" to health(answered(CompanionSessionHealth.Unavailable(DETAIL))),
        "health_timed_out" to health(RelayAnswer.TimedOut),
        "sign_out_wiped" to signOut(answered(ProfileWipe(signedOut = true, destroyed = CompanionActionResult.Ok))),
        "sign_out_failed" to
            signOut(answered(ProfileWipe(signedOut = false, destroyed = CompanionActionResult.Failed(RecGovSessionCodes.PROFILE_BUSY)))),
        "sign_out_timed_out" to signOut(RelayAnswer.TimedOut),
    )

class BookingRelayWireFixturesTest {
    @Test
    fun `the shared relay fixtures are what the relay answers`() {
        val produced: JsonElement = JsonObject(cases)
        val recorded = Json.parseToJsonElement(checkNotNull(javaClass.getResource(FIXTURE_RESOURCE)).readText())

        assertEquals(
            recorded,
            produced,
            "backend/src/test/resources$FIXTURE_RESOURCE is out of date. apps/web decodes it, so review the change, then replace " +
                "the file with:\n${prettyJson.encodeToString(JsonElement.serializer(), produced)}",
        )
    }

    @Test
    fun `every case names an operation the relay serves`() {
        val operations = setOf("hold", "sign_in", "submit_mfa", "refresh", "verify", "health", "sign_out")

        cases.values.forEach { assertEquals(true, (it[FIELD_OPERATION] as JsonPrimitive).content in operations) }
    }
}
