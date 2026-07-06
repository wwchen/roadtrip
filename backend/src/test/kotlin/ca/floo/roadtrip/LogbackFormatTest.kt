package ca.floo.roadtrip

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the production log shape the Logs/Search Grafana dashboard parses with
 * `| json`: the message must be FULLY FORMATTED (no raw `{}` pattern), and the
 * `level` / `loggerName` / nested `mdc` field names must stay stable.
 */
class LogbackFormatTest {
    @Test
    fun `json logs carry formatted message, loggerName, level, nested mdc`() {
        val captured = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(captured, true, "UTF-8"))
        try {
            // Re-apply logback.xml so the ConsoleAppender binds to the redirected stream.
            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            ctx.reset()
            JoranConfigurator().apply {
                context = ctx
                javaClass.classLoader.getResourceAsStream("logback.xml").use { doConfigure(it) }
            }
            MDC.put("run_id", "12345")
            LoggerFactory
                .getLogger("ca.floo.roadtrip.clients.recgov.HttpRecgovAvailabilityClient")
                .info("Poller: GET availability {}/{}", "232447", "2026-09-01")
            MDC.clear()
        } finally {
            System.out.flush()
            System.setOut(original)
        }

        val line = captured.toString("UTF-8").lineSequence().first { it.contains("Poller") }
        original.println("CAPTURED LOG LINE: $line")

        assertTrue(
            line.contains("\"message\":\"Poller: GET availability 232447/2026-09-01\""),
            "message not interpolated: $line",
        )
        assertTrue(
            line.contains("\"loggerName\":\"ca.floo.roadtrip.clients.recgov.HttpRecgovAvailabilityClient\""),
            "loggerName field missing/renamed: $line",
        )
        assertTrue(line.contains("\"level\":\"INFO\""), "level field missing: $line")
        assertTrue(line.contains("\"mdc\":{\"run_id\":\"12345\"}"), "mdc not nested under 'mdc': $line")
        assertFalse(line.contains("\"arguments\""), "raw arguments array should be gone: $line")
    }
}
