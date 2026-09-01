package ca.floo.roadtrip.observability

import ca.floo.roadtrip.model.domain.provider.BookingProvider

/**
 * Domain telemetry port. The OpenTelemetry Java agent auto-instruments HTTP,
 * JDBC, and the JVM, which covers "is the service up and serving" — it cannot
 * see anything this app actually *does*. Poll runs, per-provider fetch outcomes,
 * rate-limit backpressure, and whether a watch alert was delivered were only
 * ever Postgres rows and log lines, so alerting on their *rate* meant running
 * `COUNT(*)` against the serving database on every evaluation.
 *
 * These methods are the events worth counting. Row-level detail stays in
 * Postgres (`availability_run`, `availability_fetch_call`, `ingest_runs`) for
 * drill-down; this port is the rate/trend/alerting layer.
 *
 * Implementations never throw: telemetry failing must not fail the work being
 * measured.
 *
 * Attribute cardinality is deliberately bounded — every attribute below comes
 * from an enum or a registry key. Notably absent is `parent_ref` (the vendor's
 * campground identifier), which is unbounded and stays a Postgres column.
 * Outcome/status parameters are the `String` the caller already persists (an
 * enum's lowercase name) so the DB column and the metric label cannot drift.
 */
interface RoadtripMetrics {
    /** One upstream availability fetch, at the (provider, campground) group
     *  granularity `availability_fetch_call` records. [outcome] is a
     *  `FetchOutcome` name, lowercased. */
    fun availabilityFetchCompleted(
        provider: BookingProvider,
        outcome: String,
        durationMs: Int?,
    )

    /** One poll run reaching a terminal state. [status] matches the
     *  `availability_run.status` column (`completed` / `failed`). */
    fun availabilityRunFinished(
        status: String,
        durationMs: Int,
    )

    /** A poll cycle that deliberately issued no upstream call. Without this,
     *  "no fetches happened" is indistinguishable from "the poller is wedged". */
    fun availabilityPollSkipped(
        provider: BookingProvider,
        reason: PollSkipReason,
    )

    /** One trigger handler firing for a watch that detected an opening.
     *  [delivered] is the handler's own success flag — the difference between
     *  "we found the site" and "the user was actually told". */
    fun watchTriggerFired(
        kinds: Set<String>,
        delivered: Boolean,
    )

    /** One ETL ingest run reaching a terminal state. */
    fun ingestRunFinished(
        target: String,
        kind: String,
        status: String,
    )

    /**
     * One armed rec.gov profile touched by a keepalive sweep. Counting per
     * profile rather than per sweep makes the armed-set size the sum over a
     * sweep, and makes "some profiles are failing to refresh" visible as a
     * ratio rather than an all-or-nothing sweep outcome. Profile ids are
     * deliberately NOT an attribute — unbounded cardinality.
     */
    fun recgovKeepaliveProfile(outcome: KeepaliveOutcome)

    /**
     * One ATC fire reaching a terminal outcome.
     *
     * This is the path where nobody is watching: it runs from a poll sweep,
     * minutes after a site opened, and its whole value is placing a hold in
     * seconds. Row-level detail is in the result notification; this is the
     * rate/trend layer that answers "are holds still landing" and "which
     * failure is taking them".
     *
     * [error] is a `RecGovSessionCodes`-style code — a bounded registry, so it
     * is safe as an attribute; null becomes `none`. Watch ids and campsite ids
     * are deliberately absent: unbounded cardinality.
     */
    fun recgovAtcFired(
        outcome: AtcOutcome,
        error: String? = null,
        durationMs: Int? = null,
    )

    /** For tests and for any entry point that runs without the agent. */
    object NoOp : RoadtripMetrics {
        override fun availabilityFetchCompleted(
            provider: BookingProvider,
            outcome: String,
            durationMs: Int?,
        ) = Unit

        override fun availabilityRunFinished(
            status: String,
            durationMs: Int,
        ) = Unit

        override fun availabilityPollSkipped(
            provider: BookingProvider,
            reason: PollSkipReason,
        ) = Unit

        override fun watchTriggerFired(
            kinds: Set<String>,
            delivered: Boolean,
        ) = Unit

        override fun ingestRunFinished(
            target: String,
            kind: String,
            status: String,
        ) = Unit

        override fun recgovKeepaliveProfile(outcome: KeepaliveOutcome) = Unit

        override fun recgovAtcFired(
            outcome: AtcOutcome,
            error: String?,
            durationMs: Int?,
        ) = Unit
    }
}

/**
 * What a keepalive sweep managed for one armed rec.gov profile.
 *
 * Observability's own vocabulary, bounded by construction, so the port does not
 * import a service type to name its own attribute.
 */
enum class KeepaliveOutcome(
    val label: String,
) {
    /** The companion renewed the profile's session. */
    REFRESHED("refreshed"),

    /** The companion answered, but the profile could not be refreshed. */
    FAILED("failed"),

    /** The companion could not be reached at all. */
    UNAVAILABLE("unavailable"),
}

/**
 * How an ATC fire ended. Deliberately separates the three "no hold, and nobody
 * was told" shapes from an honest vendor refusal, because they have different
 * causes and different fixes.
 */
enum class AtcOutcome(
    val label: String,
) {
    /** The site was held. */
    HELD("held"),

    /** The vendor was driven and declined — taken, dates not offered, cart refused. */
    FAILED("failed"),

    /** No booking target resolved for any opening, so no attempt was made. */
    NO_TARGET("no_target"),

    /** The provider has no add-to-cart capability for this target. */
    UNSUPPORTED("unsupported"),

    /** The attempt threw before producing a result. */
    EXCEPTION("exception"),
}

/** Why a poll cycle issued no upstream call. Observability's own vocabulary, so
 *  the port does not have to import a service type to name its own attribute. */
enum class PollSkipReason(
    val label: String,
) {
    /** Every group already had coverage fresher than the poller's cadence. */
    COVERAGE_FRESH("coverage_fresh"),

    /** The vendor rate-limit governor had no tokens; the cycle was rescheduled. */
    GOVERNOR_STARVED("governor_starved"),
}
