package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.route.api.pois.mapProviderError
import ca.floo.roadtrip.route.api.pois.upstreamHttpStatus
import ca.floo.roadtrip.service.availability.provider.upstreamAvailabilityError
import ca.floo.roadtrip.support.AspiraException
import ca.floo.roadtrip.support.CampflareException
import ca.floo.roadtrip.support.ReserveAmericaException
import ca.floo.roadtrip.support.ReserveCaliforniaException
import ca.floo.roadtrip.support.causeChain
import io.ktor.http.HttpStatusCode
import java.net.ConnectException
import java.nio.channels.ClosedChannelException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the shape of the 2026-07-30 prod incident: every Aspira availability
 * request 503'd with `upstream_5xx` and the log line said only
 * "failed: upstream_5xx" — the real fault (a JDK HttpClient ConnectException
 * caused by ClosedChannelException) existed solely in the Tempo span.
 */
class CampsiteErrorLoggingTest {
    /** The exact chain prod produced, rebuilt from the trace's exception event. */
    private fun transportFailure(): AvailabilityProviderError {
        val closed = ClosedChannelException()
        val connect = ConnectException().apply { initCause(closed) }
        val aspira =
            AspiraException(
                "aspira request failed for mapId=-2147483306 host=camping.bcparks.ca: " +
                    "java.net.ConnectException: null",
                httpStatus = null,
                cause = connect,
            )
        return AvailabilityProviderError.UpstreamUnavailable(aspira)
    }

    @Test
    fun `transport failure reports no upstream status`() {
        // Nothing upstream answered, so there is no HTTP status to report.
        // This is what made the 503 body indistinguishable from a real 5xx.
        assertEquals(null, upstreamHttpStatus(transportFailure()))
    }

    @Test
    fun `provider error message alone identifies nothing`() {
        // The regression: UpstreamUnavailable's message IS its classification,
        // so logging e.message logged the bucket name and nothing else.
        assertEquals("upstream_5xx", transportFailure().message)
    }

    @Test
    fun `cause chain surfaces the underlying transport exception`() {
        val rendered = causeChain(transportFailure())

        assertTrue(rendered.contains("java.net.ConnectException"), rendered)
        assertTrue(rendered.contains("java.nio.channels.ClosedChannelException"), rendered)
        assertTrue(rendered.contains("camping.bcparks.ca"), rendered)
        assertTrue(rendered.contains("mapId=-2147483306"), rendered)
    }

    @Test
    fun `cause chain terminates on a self-referential chain`() {
        // A cycle must not spin or flood the log line.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        val rendered = causeChain(b)

        assertEquals("java.lang.RuntimeException: b <- java.lang.RuntimeException: a", rendered)
    }

    @Test
    fun `a connect failure classifies as unreachable, not upstream_5xx`() {
        val connect = ConnectException().apply { initCause(ClosedChannelException()) }
        val aspira = AspiraException("aspira request failed", httpStatus = null, cause = connect)

        val classified = upstreamAvailabilityError(cause = aspira, httpStatus = null)

        assertTrue(classified is AvailabilityProviderError.UpstreamUnreachable, classified.toString())
        val (status, dto) = mapProviderError(classified)
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals("upstream_unreachable", dto.error)
        assertEquals(null, dto.upstreamStatus)
        // The throwable must survive classification, or the log says nothing.
        assertTrue(causeChain(classified).contains("ClosedChannelException"), causeChain(classified))
    }

    @Test
    fun `an unconfigured tenant is not reported as an upstream failure`() {
        val e =
            AvailabilityProviderError.Misconfigured(
                providerId = "aspira",
                reason = "tenant 'bcparks' is not configured",
                cause = IllegalArgumentException("aspira tenant 'bcparks' is not configured"),
            )

        val (status, dto) = mapProviderError(e)

        assertEquals(HttpStatusCode.InternalServerError, status)
        assertEquals("provider_misconfigured", dto.error)
        assertTrue(causeChain(e).contains("IllegalArgumentException"), causeChain(e))
    }

    @Test
    fun `a real upstream 5xx still reports its status`() {
        val e =
            AvailabilityProviderError.UpstreamUnavailable(
                AspiraException("aspira HTTP 502 for mapId=1", httpStatus = 502),
            )

        val (_, dto) = mapProviderError(e)

        assertEquals("upstream_5xx", dto.error)
        assertEquals(502, dto.upstreamStatus)
    }

    @Test
    fun `every provider wrapper surfaces its upstream status, not just Aspira`() {
        // upstreamHttpStatus once matched AspiraException alone, so a
        // Campflare/ReserveAmerica/ReserveCalifornia 5xx dropped the status
        // field the runbook promises. All four now carry it through.
        val wrappers =
            listOf(
                AspiraException("aspira HTTP 503", httpStatus = 503),
                CampflareException("campflare HTTP 503", httpStatus = 503),
                ReserveAmericaException("reserveamerica HTTP 503", httpStatus = 503),
                ReserveCaliforniaException("reservecalifornia HTTP 503", httpStatus = 503),
            )

        for (wrapper in wrappers) {
            val e = AvailabilityProviderError.UpstreamUnavailable(wrapper)
            val (_, dto) = mapProviderError(e)
            assertEquals("upstream_5xx", dto.error, wrapper.toString())
            assertEquals(503, dto.upstreamStatus, wrapper.toString())
        }
    }

    @Test
    fun `upstream status extraction terminates on a self-referential chain`() {
        // upstreamHttpStatus walks the same chain causeChain does; without the
        // depth/cycle guard a self-referential cause would spin here too.
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        val e = AvailabilityProviderError.UpstreamUnavailable(b)

        assertEquals(null, upstreamHttpStatus(e))
    }
}
