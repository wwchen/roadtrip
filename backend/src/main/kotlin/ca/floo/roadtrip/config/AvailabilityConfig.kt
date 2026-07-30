package ca.floo.roadtrip.config

import java.time.Duration

data class AvailabilityConfig(
    val forcePullCooldown: Duration,
    val providerCooldown: Duration,
    val poller: AvailabilityPollerConfig,
) {
    companion object {
        fun fromConfig(config: ConfigSection): AvailabilityConfig =
            AvailabilityConfig(
                forcePullCooldown = config.requiredDuration("force-pull-cooldown"),
                providerCooldown = config.requiredDuration("provider-cooldown"),
                poller = AvailabilityPollerConfig.fromConfig(config.section("poller")),
            )
    }
}

/**
 * Poller timings that are operationally tunable: the cadence floor every watch
 * falls back to, and the two reschedule delays the executor picks when it does
 * not poll. Defaults are in code so a missing config section keeps today's
 * behavior; `application.yaml` states them explicitly so an operator can see
 * and change them without a rebuild.
 */
data class AvailabilityPollerConfig(
    val defaultCadence: Duration,
    val idleReschedule: Duration,
    val governorStarvedRetry: Duration,
) {
    init {
        // Sub-second values truncate to 0 at the `.seconds` call sites (here and
        // in the poll executor), turning the freshness window and reschedule
        // delays into a hot loop. Whole seconds only, and cadence must fit Int.
        requireWholeSeconds("default-cadence", defaultCadence)
        requireWholeSeconds("idle-reschedule", idleReschedule)
        requireWholeSeconds("governor-starved-retry", governorStarvedRetry)
        require(defaultCadence.seconds <= Int.MAX_VALUE) {
            "poller default-cadence must fit in Int seconds (got $defaultCadence)"
        }
    }

    val defaultCadenceSec: Int get() = defaultCadence.seconds.toInt()

    companion object {
        /** Last rung of the `watch → POI → global` cadence fall-through. */
        private const val DEFAULT_CADENCE_SEC = 300L

        /** A poller with no live work re-checks on this interval. */
        private const val DEFAULT_IDLE_RESCHEDULE_SEC = 300L

        /** Vendor governor had no tokens: retry sooner than a full cadence. */
        private const val DEFAULT_GOVERNOR_STARVED_RETRY_SEC = 15L

        /** For call sites that legitimately have no config (tests, and read-path
         *  wiring that never resolves a cadence). Production wires the real one. */
        val default =
            AvailabilityPollerConfig(
                defaultCadence = Duration.ofSeconds(DEFAULT_CADENCE_SEC),
                idleReschedule = Duration.ofSeconds(DEFAULT_IDLE_RESCHEDULE_SEC),
                governorStarvedRetry = Duration.ofSeconds(DEFAULT_GOVERNOR_STARVED_RETRY_SEC),
            )

        fun fromConfig(config: ConfigSection): AvailabilityPollerConfig =
            AvailabilityPollerConfig(
                defaultCadence = config.duration("default-cadence", default.defaultCadence),
                idleReschedule = config.duration("idle-reschedule", default.idleReschedule),
                governorStarvedRetry = config.duration("governor-starved-retry", default.governorStarvedRetry),
            )

        private fun requireWholeSeconds(
            name: String,
            value: Duration,
        ) {
            require(value.seconds >= 1 && value.nano == 0) {
                "poller $name must be a whole number of seconds, at least 1s (got $value)"
            }
        }
    }
}
