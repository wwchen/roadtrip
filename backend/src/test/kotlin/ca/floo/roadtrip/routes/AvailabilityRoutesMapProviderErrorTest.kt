package ca.floo.roadtrip.routes

import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvailabilityRoutesMapProviderErrorTest {
    @Test
    fun `rate limited surfaces 503 and upstream status from Aspira cause`() {
        val (status, dto) = mapProviderError(ReservationProviderError.RateLimited(AspiraException("429", httpStatus = 429)))
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals("rate_limited", dto.error)
        assertEquals(429, dto.upstreamStatus)
    }

    @Test
    fun `upstream blocked surfaces 503 and upstream status`() {
        val (status, dto) = mapProviderError(ReservationProviderError.UpstreamBlocked(AspiraException("WAF", httpStatus = 503)))
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals("upstream_blocked", dto.error)
        assertEquals(503, dto.upstreamStatus)
    }

    @Test
    fun `upstream unavailable walks nested causes to find AspiraException`() {
        val nested = RuntimeException("wrap", AspiraException("502", httpStatus = 502))
        val (status, dto) = mapProviderError(ReservationProviderError.UpstreamUnavailable(nested))
        assertEquals(HttpStatusCode.ServiceUnavailable, status)
        assertEquals("upstream_5xx", dto.error)
        assertEquals(502, dto.upstreamStatus)
    }

    @Test
    fun `upstream status is null when cause is not Aspira`() {
        val (_, dto) = mapProviderError(ReservationProviderError.UpstreamUnavailable(RuntimeException("network")))
        assertNull(dto.upstreamStatus)
    }

    @Test
    fun `unsupported maps to 501 with no upstream status`() {
        val (status, dto) = mapProviderError(ReservationProviderError.Unsupported("availability", ReservationProviderId.ASPIRA))
        assertEquals(HttpStatusCode.NotImplemented, status)
        assertEquals("unsupported", dto.error)
        assertNull(dto.upstreamStatus)
    }

    @Test
    fun `wrong ref type maps to 500 provider_misconfigured`() {
        val (status, dto) = mapProviderError(ReservationProviderError.WrongRefType(ReservationProviderId.ASPIRA, "RecGovRef"))
        assertEquals(HttpStatusCode.InternalServerError, status)
        assertEquals("provider_misconfigured", dto.error)
    }

    @Test
    fun `upstreamHttpStatus returns null when there is no cause`() {
        assertNull(upstreamHttpStatus(ReservationProviderError.RateLimited(null)))
    }
}
