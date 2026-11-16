package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.service.settings.CompanionActionResult
import ca.floo.roadtrip.service.settings.CompanionSessionHealth
import ca.floo.roadtrip.service.settings.RecGovSessionCodes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROFILE_ID = "n:7"
private const val VENDOR_SITE_ID = "300"
private val arrival: LocalDate = LocalDate.of(2026, 12, 1)
private val checkout: LocalDate = LocalDate.of(2026, 12, 2)

class RecGovProfileHoldTest {
    private class FakeSession(
        var health: CompanionSessionHealth,
        var refreshResult: CompanionActionResult = CompanionActionResult.Ok,
    ) : RecGovHoldSession {
        var refreshes = 0

        override suspend fun health(): CompanionSessionHealth = health

        override suspend fun refresh(): CompanionActionResult {
            refreshes++
            return refreshResult
        }
    }

    private class FakeExecutor(
        var outcome: RecGovAtcOutcome = RecGovAtcOutcome.Completed(buildJsonObject { put("cart_added", true) }),
    ) : RecGovAtcExecutor {
        val payloads = mutableListOf<JsonObject>()

        override suspend fun addToCart(payload: JsonObject): RecGovAtcOutcome {
            payloads += payload
            return outcome
        }
    }

    private val request = ProfileHoldRequest(PROFILE_ID, VENDOR_SITE_ID, arrival, checkout)

    @Test
    fun `active session holds without refresh and passes the profile id verbatim`() =
        runBlocking {
            val session = FakeSession(CompanionSessionHealth.Active)
            val executor = FakeExecutor()

            val outcome = RecGovProfileHold(executor).hold(session, request)

            val held = assertIs<ProfileHoldOutcome.Held>(outcome)
            assertEquals(RECGOV_CART_URL, held.cartUrl)
            assertEquals(0, session.refreshes)
            val payload = executor.payloads.single()
            assertEquals(PROFILE_ID, payload["profile_id"]?.jsonPrimitive?.content)
            assertEquals(VENDOR_SITE_ID, payload["campsite_id"]?.jsonPrimitive?.content)
            assertEquals("2026-12-01", payload["start_date"]?.jsonPrimitive?.content)
            assertEquals("2026-12-02", payload["end_date"]?.jsonPrimitive?.content)
        }

    @Test
    fun `inactive session refreshes then holds`() =
        runBlocking {
            val session = FakeSession(CompanionSessionHealth.Inactive(RecGovSessionCodes.SESSION_LAPSED))
            val executor = FakeExecutor()

            val outcome = RecGovProfileHold(executor).hold(session, request)

            assertIs<ProfileHoldOutcome.Held>(outcome)
            assertEquals(1, session.refreshes)
            assertEquals(1, executor.payloads.size)
        }

    @Test
    fun `refresh failure without relogin blocks with session expired`() =
        runBlocking {
            val session =
                FakeSession(
                    CompanionSessionHealth.NeverLoggedIn,
                    CompanionActionResult.Failed(RecGovSessionCodes.REFRESH_FAILED, null),
                )
            val executor = FakeExecutor()

            val outcome = RecGovProfileHold(executor).hold(session, request)

            val blocked = assertIs<ProfileHoldOutcome.Blocked>(outcome)
            assertEquals(RECGOV_SESSION_EXPIRED_ERROR, blocked.code)
            assertEquals(BookingFailureCategory.CALLER_ACTION, blocked.category)
            assertTrue(executor.payloads.isEmpty())
        }

    @Test
    fun `refresh failure hands the relogin its health code`() =
        runBlocking {
            val session =
                FakeSession(
                    CompanionSessionHealth.Inactive(RecGovSessionCodes.SESSION_LAPSED),
                    CompanionActionResult.Failed(RecGovSessionCodes.REFRESH_FAILED, null),
                )
            var seen: String? = null

            val outcome =
                RecGovProfileHold(FakeExecutor()).hold(session, request) { code ->
                    seen = code
                    null
                }

            assertIs<ProfileHoldOutcome.Held>(outcome)
            assertEquals(RecGovSessionCodes.SESSION_LAPSED, seen)
        }

    @Test
    fun `companion codes map through the category table`() =
        runBlocking {
            val executor = FakeExecutor(RecGovAtcOutcome.Failed(RecGovSessionCodes.PROFILE_BUSY, null))
            val hold = RecGovProfileHold(executor)

            val busy = assertIs<ProfileHoldOutcome.Blocked>(hold.hold(FakeSession(CompanionSessionHealth.Active), request))
            assertEquals(BookingFailureCategory.RETRY_LATER, busy.category)

            executor.outcome = RecGovAtcOutcome.Failed("something_new", "odd")
            val unknown = assertIs<ProfileHoldOutcome.Blocked>(hold.hold(FakeSession(CompanionSessionHealth.Active), request))
            assertEquals(BookingFailureCategory.UPSTREAM, unknown.category)
            assertEquals("odd", unknown.detail)
        }

    @Test
    fun `an unexplained refusal gets its category's sentence`() =
        runBlocking {
            val executor = FakeExecutor(RecGovAtcOutcome.Failed(BookingActionCodes.CART_NOT_ADDED, null))

            val blocked =
                assertIs<ProfileHoldOutcome.Blocked>(
                    RecGovProfileHold(executor).hold(FakeSession(CompanionSessionHealth.Active), request),
                )

            assertEquals(BookingFailureCategory.RETRY_LATER, blocked.category)
            assertTrue(blocked.detail.isNotBlank())
        }

    @Test
    fun `no session skips the preflight`() {
        val outcome = runBlocking { RecGovProfileHold(FakeExecutor()).hold(null, request) }

        assertIs<ProfileHoldOutcome.Held>(outcome)
    }

    @Test
    fun `an unavailable companion blocks before the hold`() =
        runBlocking {
            val executor = FakeExecutor()

            val blocked =
                assertIs<ProfileHoldOutcome.Blocked>(
                    RecGovProfileHold(executor).hold(FakeSession(CompanionSessionHealth.Unavailable(null)), request),
                )

            assertEquals(RecGovSessionCodes.COMPANION_UNAVAILABLE, blocked.code)
            assertNull(blocked.response)
            assertTrue(executor.payloads.isEmpty())
        }
}
