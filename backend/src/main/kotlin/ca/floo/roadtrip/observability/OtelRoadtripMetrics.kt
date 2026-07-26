package ca.floo.roadtrip.observability

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

// Instrument names. OTLP dots become underscores in Prometheus, and counters
// gain a `_total` suffix: `roadtrip.availability.fetch` is queryable as
// `roadtrip_availability_fetch_total`.
private const val METER_NAME = "ca.floo.roadtrip"
private const val METRIC_FETCH = "roadtrip.availability.fetch"
private const val METRIC_FETCH_DURATION = "roadtrip.availability.fetch.duration"
private const val METRIC_RUN = "roadtrip.availability.run"
private const val METRIC_RUN_DURATION = "roadtrip.availability.run.duration"
private const val METRIC_POLL_SKIPPED = "roadtrip.availability.poll.skipped"
private const val METRIC_WATCH_TRIGGER = "roadtrip.watch.trigger"
private const val METRIC_INGEST_RUN = "roadtrip.ingest.run"

private const val UNIT_MILLISECONDS = "ms"
private const val UNIT_CALLS = "{call}"
private const val UNIT_RUNS = "{run}"
private const val UNIT_CYCLES = "{cycle}"
private const val UNIT_TRIGGERS = "{trigger}"

// Attribute keys are allocated once; building them per-call would allocate on
// every fetch.
private val attrProvider = AttributeKey.stringKey("provider")
private val attrOutcome = AttributeKey.stringKey("outcome")
private val attrStatus = AttributeKey.stringKey("status")
private val attrReason = AttributeKey.stringKey("reason")
private val attrKinds = AttributeKey.stringKey("kinds")
private val attrDelivered = AttributeKey.booleanKey("delivered")
private val attrTarget = AttributeKey.stringKey("target")
private val attrKind = AttributeKey.stringKey("kind")

/** Separator for the trigger-kind set. Sorted + joined so a watch with the same
 *  handlers always lands on the same series regardless of set iteration order. */
private const val KIND_SEPARATOR = "+"

/**
 * OpenTelemetry-backed [RoadtripMetrics].
 *
 * No exporter wiring lives here. The Java agent (see `Dockerfile` and the
 * `OTEL_*` env in `docker-compose.yml`) owns the OTLP pipeline to Alloy, and
 * [GlobalOpenTelemetry] resolves to the agent's SDK, so these instruments ride
 * the same connection as the auto-instrumentation. Started without the agent —
 * plain `make run`, or a unit test — [GlobalOpenTelemetry.get] returns a no-op
 * implementation, so recording is a cheap no-op rather than an error.
 */
internal class OtelRoadtripMetrics(
    openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
) : RoadtripMetrics {
    private val meter = openTelemetry.getMeter(METER_NAME)

    private val fetches =
        meter
            .counterBuilder(METRIC_FETCH)
            .setDescription("Upstream availability fetches, by provider and outcome")
            .setUnit(UNIT_CALLS)
            .build()

    private val fetchDuration =
        meter
            .histogramBuilder(METRIC_FETCH_DURATION)
            .ofLongs()
            .setDescription("Upstream availability fetch latency")
            .setUnit(UNIT_MILLISECONDS)
            .build()

    private val runs =
        meter
            .counterBuilder(METRIC_RUN)
            .setDescription("Availability poll runs reaching a terminal state")
            .setUnit(UNIT_RUNS)
            .build()

    private val runDuration =
        meter
            .histogramBuilder(METRIC_RUN_DURATION)
            .ofLongs()
            .setDescription("Availability poll run wall-clock duration")
            .setUnit(UNIT_MILLISECONDS)
            .build()

    private val pollSkips =
        meter
            .counterBuilder(METRIC_POLL_SKIPPED)
            .setDescription("Poll cycles that issued no upstream call, by reason")
            .setUnit(UNIT_CYCLES)
            .build()

    private val watchTriggers =
        meter
            .counterBuilder(METRIC_WATCH_TRIGGER)
            .setDescription("Watch trigger handler fires, by kind and delivery outcome")
            .setUnit(UNIT_TRIGGERS)
            .build()

    private val ingestRuns =
        meter
            .counterBuilder(METRIC_INGEST_RUN)
            .setDescription("ETL ingest runs reaching a terminal state")
            .setUnit(UNIT_RUNS)
            .build()

    override fun availabilityFetchCompleted(
        provider: BookingProvider,
        outcome: String,
        durationMs: Int?,
    ) {
        val attributes = Attributes.of(attrProvider, provider.id, attrOutcome, outcome)
        fetches.add(1, attributes)
        // Null when the adapter never got far enough to time a call; counting it
        // in the histogram as 0ms would drag every latency percentile down.
        durationMs?.let { fetchDuration.record(it.toLong(), attributes) }
    }

    override fun availabilityRunFinished(
        status: String,
        durationMs: Int,
    ) {
        val attributes = Attributes.of(attrStatus, status)
        runs.add(1, attributes)
        runDuration.record(durationMs.toLong(), attributes)
    }

    override fun availabilityPollSkipped(
        provider: BookingProvider,
        reason: PollSkipReason,
    ) {
        pollSkips.add(1, Attributes.of(attrProvider, provider.id, attrReason, reason.label))
    }

    override fun watchTriggerFired(
        kinds: Set<String>,
        delivered: Boolean,
    ) {
        watchTriggers.add(
            1,
            Attributes.of(
                attrKinds,
                kinds.sorted().joinToString(KIND_SEPARATOR),
                attrDelivered,
                delivered,
            ),
        )
    }

    override fun ingestRunFinished(
        target: String,
        kind: String,
        status: String,
    ) {
        ingestRuns.add(1, Attributes.of(attrTarget, target, attrKind, kind, attrStatus, status))
    }
}
