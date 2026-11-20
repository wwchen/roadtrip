package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.observability.RoadtripMetrics
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnrecognizedStatusReporterTest {
    private val events = ListAppender<ILoggingEvent>().apply { start() }
    private val logger =
        LoggerContext().getLogger("unrecognized-status-capture").apply { addAppender(events) }
    private val counted = mutableListOf<Pair<BookingProvider, Int>>()
    private val metrics =
        object : RoadtripMetrics by RoadtripMetrics.NoOp {
            override fun availabilityStatusUnrecognized(
                provider: BookingProvider,
                cells: Int,
            ) {
                counted += provider to cells
            }
        }
    private var now: Instant = Instant.parse("2026-11-20T12:00:00Z")
    private val reporter = UnrecognizedStatusReporter(metrics = metrics, clock = { now }, log = logger)

    @Test
    fun `a value the vendor keeps sending is logged once per interval but counted every time`() {
        reporter.report(BookingProvider.RECGOV, mapOf("Lottery" to 3))
        now = now.plus(Duration.ofMinutes(5))
        reporter.report(BookingProvider.RECGOV, mapOf("Lottery" to 2))

        assertEquals(1, events.list.size)
        assertTrue(
            events.list
                .single()
                .formattedMessage
                .contains("Lottery"),
        )
        assertEquals(listOf(BookingProvider.RECGOV to 3, BookingProvider.RECGOV to 2), counted)
    }

    @Test
    fun `a value is logged again once the interval has passed`() {
        reporter.report(BookingProvider.RECGOV, mapOf("Lottery" to 1))
        now = now.plus(unrecognizedStatusLogInterval).plusSeconds(1)
        reporter.report(BookingProvider.RECGOV, mapOf("Lottery" to 1))

        assertEquals(2, events.list.size)
    }

    @Test
    fun `a new value, or the same value from another provider, is logged at once`() {
        reporter.report(BookingProvider.RECGOV, mapOf("Lottery" to 1))
        reporter.report(BookingProvider.RECGOV, mapOf("Lottery" to 1, "Walk-Up Only" to 1))
        reporter.report(BookingProvider.CAMPFLARE, mapOf("Lottery" to 1))

        assertEquals(3, events.list.size)
        assertTrue(events.list[1].formattedMessage.contains("Walk-Up Only"))
    }

    @Test
    fun `nothing unrecognized logs and counts nothing`() {
        reporter.report(BookingProvider.RECGOV, emptyMap())

        assertEquals(emptyList(), events.list.toList())
        assertEquals(emptyList(), counted)
    }
}
