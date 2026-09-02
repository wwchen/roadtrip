package ca.floo.roadtrip.observability

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * These assertions are the contract between this class and
 * `grafana/provisioning/alerting/roadtrip.yml` + the Prometheus dashboards: the
 * instrument names below become `roadtrip_availability_fetch_total` and friends
 * after OTLP -> Prometheus naming conversion, and the attribute keys become
 * label names. Renaming one without the other silently empties a panel or wedges
 * an alert at "no data", which is the exact failure this suite exists to catch.
 */
class OtelRoadtripMetricsTest {
    private val reader = InMemoryMetricReader.create()
    private val sdk =
        OpenTelemetrySdk
            .builder()
            .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(reader).build())
            .build()
    private val metrics = OtelRoadtripMetrics(sdk)

    private fun collect(): Map<String, MetricData> = reader.collectAllMetrics().associateBy { it.name }

    private fun MetricData.longSumAttributes(): Map<String, String> =
        longSumData.points
            .single()
            .attributes
            .asMap()
            .entries
            .associate { (key, value) -> key.key to value.toString() }

    @Test
    fun `fetch outcome is counted and timed per provider`() {
        metrics.availabilityFetchCompleted(BookingProvider.RECGOV, "rate_limited", durationMs = 42)

        val collected = collect()
        val counter = collected.getValue("roadtrip.availability.fetch")
        assertEquals(
            1,
            counter.longSumData.points
                .single()
                .value,
        )
        assertEquals(
            mapOf("provider" to "recgov", "outcome" to "rate_limited"),
            counter.longSumAttributes(),
        )

        val histogram = collected.getValue("roadtrip.availability.fetch.duration")
        val point = histogram.histogramData.points.single()
        assertEquals(1, point.count)
        assertEquals(42.0, point.sum)
    }

    @Test
    fun `fetch with no timing is counted but not recorded as zero latency`() {
        metrics.availabilityFetchCompleted(BookingProvider.ASPIRA, "blocked", durationMs = null)

        val collected = collect()
        assertEquals(
            1,
            collected
                .getValue("roadtrip.availability.fetch")
                .longSumData.points
                .single()
                .value,
        )
        // A null duration means the adapter never timed a call. Recording 0ms
        // would drag every latency percentile toward zero.
        assertTrue("roadtrip.availability.fetch.duration" !in collected)
    }

    @Test
    fun `run status is counted and timed`() {
        metrics.availabilityRunFinished("failed", durationMs = 1_500)

        val collected = collect()
        assertEquals(
            mapOf("status" to "failed"),
            collected.getValue("roadtrip.availability.run").longSumAttributes(),
        )
        assertEquals(
            1_500.0,
            collected
                .getValue("roadtrip.availability.run.duration")
                .histogramData.points
                .single()
                .sum,
        )
    }

    @Test
    fun `poll skip carries its reason label`() {
        metrics.availabilityPollSkipped(BookingProvider.CAMPFLARE, PollSkipReason.GOVERNOR_STARVED)

        assertEquals(
            mapOf("provider" to "campflare", "reason" to "governor_starved"),
            collect().getValue("roadtrip.availability.poll.skipped").longSumAttributes(),
        )
    }

    @Test
    fun `trigger kinds are sorted so set order cannot split the series`() {
        metrics.watchTriggerFired(setOf("slack_notify", "email_notify"), delivered = true)
        metrics.watchTriggerFired(setOf("email_notify", "slack_notify"), delivered = true)

        val point =
            collect()
                .getValue("roadtrip.watch.trigger")
                .longSumData.points
                .single()
        assertEquals(2, point.value)
        assertEquals(
            "email_notify+slack_notify",
            point.attributes
                .asMap()
                .entries
                .first { it.key.key == "kinds" }
                .value,
        )
    }

    @Test
    fun `delivery failure is a distinct series from delivery success`() {
        metrics.watchTriggerFired(setOf("slack_notify"), delivered = true)
        metrics.watchTriggerFired(setOf("slack_notify"), delivered = false)

        assertEquals(
            2,
            collect()
                .getValue("roadtrip.watch.trigger")
                .longSumData.points.size,
        )
    }

    @Test
    fun `ingest run carries target kind and status`() {
        metrics.ingestRunFinished(target = "Planet Fitness", kind = "import", status = "completed")

        assertEquals(
            mapOf("target" to "Planet Fitness", "kind" to "import", "status" to "completed"),
            collect().getValue("roadtrip.ingest.run").longSumAttributes(),
        )
    }

    @Test
    fun `keepalive counts one armed profile per outcome`() {
        metrics.recgovKeepaliveProfile(KeepaliveOutcome.REFRESHED)
        metrics.recgovKeepaliveProfile(KeepaliveOutcome.UNAVAILABLE)

        val points =
            collect()
                .getValue("roadtrip.recgov.keepalive")
                .longSumData.points
                .associate { point ->
                    point.attributes
                        .asMap()
                        .values
                        .single()
                        .toString() to point.value
                }

        assertEquals(mapOf("refreshed" to 1L, "unavailable" to 1L), points)
    }
}
