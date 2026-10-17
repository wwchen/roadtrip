package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.StayFailureReason
import ca.floo.roadtrip.model.api.StayOutcome
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The server's own reading of a campground's failure: which outcome it is, why,
 * and whether asking again can help. The codes below are the literal strings
 * [AvailabilityProviderError] and [AvailabilityServiceError] put on the wire.
 */
class StayFailureClassifierTest {
    @Test fun `a horizon refusal is refused and carries no retryable`() {
        assertEquals(
            StayFailure(StayOutcome.REFUSED, StayFailureReason.BEYOND_BOOKING_HORIZON, null),
            stayFailureOf("beyond_booking_horizon"),
        )
    }

    @Test fun `the timeout is its own outcome with no reason and no retryable`() {
        assertEquals(StayOutcome.TIMED_OUT, stayFailureOf(STAY_TIMEOUT_ERROR).outcome)
        assertNull(stayFailureOf(STAY_TIMEOUT_ERROR).retryable)
        assertNull(stayFailureOf(STAY_TIMEOUT_ERROR).reason)
    }

    @Test fun `an upstream failure is failed and retryable`() {
        assertEquals(
            StayFailure(StayOutcome.FAILED, StayFailureReason.UPSTREAM_5XX, true),
            stayFailureOf("upstream_5xx"),
        )
    }

    @Test fun `a misconfigured provider is failed and not retryable`() {
        assertEquals(
            StayFailure(StayOutcome.FAILED, StayFailureReason.PROVIDER_MISCONFIGURED, false),
            stayFailureOf("provider_misconfigured"),
        )
    }

    @Test fun `an unknown poi is failed and not retryable, never refused`() {
        assertEquals(
            StayFailure(StayOutcome.FAILED, StayFailureReason.NOT_FOUND, false),
            stayFailureOf("not_found"),
        )
    }

    @Test fun `a code outside the vocabulary falls back to unknown and not retryable`() {
        assertEquals(
            StayFailure(StayOutcome.FAILED, StayFailureReason.UNKNOWN, false),
            stayFailureOf("end_before_start"),
        )
    }

    @Test fun `every provider and service error code classifies`() {
        // The expected map is the vocabulary, spelled out literally. It pins what every code in
        // both hierarchies resolves to today.
        val expected =
            mapOf(
                // AvailabilityServiceError.BadDateWindow. The two the route refuses for the whole
                // request before any fan-out (end_before_start, bad_date_window) are absent on purpose.
                "start_before_earliest" to
                    StayFailure(StayOutcome.REFUSED, StayFailureReason.START_BEFORE_EARLIEST, null),
                "window_too_long" to
                    StayFailure(StayOutcome.REFUSED, StayFailureReason.WINDOW_TOO_LONG, null),
                "beyond_booking_horizon" to
                    StayFailure(StayOutcome.REFUSED, StayFailureReason.BEYOND_BOOKING_HORIZON, null),
                // The rest of AvailabilityServiceError.
                "not_found" to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.NOT_FOUND, false),
                "unknown_campground" to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.UNKNOWN_CAMPGROUND, false),
                // AvailabilityProviderError.
                "rate_limited" to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.RATE_LIMITED, true),
                "upstream_blocked" to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.UPSTREAM_BLOCKED, true),
                "upstream_5xx" to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.UPSTREAM_5XX, true),
                "upstream_unreachable" to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.UPSTREAM_UNREACHABLE, true),
                "provider_misconfigured" to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.PROVIDER_MISCONFIGURED, false),
                "unsupported" to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.UNSUPPORTED, false),
                // The one code the classifier names UNKNOWN on purpose.
                AvailabilityProviderError.Unknown.CODE to
                    StayFailure(StayOutcome.FAILED, StayFailureReason.UNKNOWN, false),
            )
        expected.forEach { (code, failure) -> assertEquals(failure, stayFailureOf(code), "code $code") }

        // Hand-maintained. This list is NOT derived from the sealed hierarchies, so a new
        // AvailabilityProviderError or AvailabilityServiceError subclass is simply absent from it:
        // this test keeps passing and the new code silently classifies as `unknown`. Whoever adds
        // such a subclass must add a constructor call here and a row to `expected` above by hand,
        // along with a branch in stayFailureOf.
        val carried =
            listOf(
                AvailabilityProviderError.RateLimited().code,
                AvailabilityProviderError.UpstreamBlocked().code,
                AvailabilityProviderError.UpstreamUnavailable(RuntimeException()).code,
                AvailabilityProviderError.UpstreamUnreachable(RuntimeException()).code,
                AvailabilityProviderError.Misconfigured("p", "r", RuntimeException()).code,
                AvailabilityProviderError.Unsupported("op", "p").code,
                AvailabilityProviderError.WrongRefType("p", "t").code,
                AvailabilityServiceError.BadDateWindow.StartBeforeEarliest(LocalDate.EPOCH, ZoneOffset.UTC).error,
                AvailabilityServiceError.BadDateWindow.WindowTooLong(1).error,
                AvailabilityServiceError.BadDateWindow.BeyondBookingHorizon(LocalDate.EPOCH).error,
                AvailabilityServiceError.NotFound.error,
                AvailabilityServiceError.UnknownCampground.error,
            )
        carried.forEach { code ->
            assertNotEquals(StayFailureReason.UNKNOWN, stayFailureOf(code).reason, "code $code")
            assertEquals(expected.getValue(code), stayFailureOf(code), "code $code")
        }
    }
}
