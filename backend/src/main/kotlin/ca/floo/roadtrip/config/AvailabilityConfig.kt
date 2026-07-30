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
    }
}
