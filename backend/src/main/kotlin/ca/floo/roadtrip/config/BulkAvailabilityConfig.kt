package ca.floo.roadtrip.config

import java.time.Duration

/**
 * Caps and timings for the bulk availability read. A bulk scan is the one read
 * that can turn a single request into many upstream calls, so every bound here
 * is operationally tunable.
 */
data class BulkAvailabilityConfig(
    val maxPois: Int,
    val fanOutConcurrency: Int,
    /** How old a stored observation may be and still answer, rather than asking the provider again. */
    val tolerance: Duration,
    val ipRateLimitPerMinute: Int,
    /**
     * [tolerance] for a request that asks for fresh data (`fresh: true`, the map's
     * Check again). Short rather than zero, so a double-click does not ask every
     * provider twice; never longer than [tolerance], which it defaults down to.
     */
    val freshTolerance: Duration = minOf(tolerance, Duration.ofSeconds(DEFAULT_FRESH_TOLERANCE_SECONDS)),
) {
    init {
        require(maxPois >= 1) { "bulk max-pois must be >= 1 (got $maxPois)" }
        require(fanOutConcurrency >= 1) { "bulk fan-out-concurrency must be >= 1 (got $fanOutConcurrency)" }
        require(!tolerance.isNegative) { "bulk tolerance must not be negative (got $tolerance)" }
        require(ipRateLimitPerMinute >= 1) {
            "bulk ip-rate-limit-per-minute must be >= 1 (got $ipRateLimitPerMinute)"
        }
        require(!freshTolerance.isNegative && freshTolerance <= tolerance) {
            "bulk fresh-tolerance must be between 0 and tolerance ($tolerance) (got $freshTolerance)"
        }
    }

    companion object {
        private const val DEFAULT_MAX_POIS = 50

        // Must stay below db.max-pool-size (currently DbConfig.DEFAULT_MAX_POOL_SIZE = 4):
        // each concurrent POI can hold a pooled connection through
        // AvailabilityRepo.recordObservations, so fan-out at or above the pool size
        // guarantees Hikari connection-acquisition timeouts.
        private const val DEFAULT_FAN_OUT_CONCURRENCY = 3
        private const val DEFAULT_TOLERANCE_HOURS = 2L
        private const val DEFAULT_IP_RATE_LIMIT_PER_MINUTE = 10
        private const val DEFAULT_FRESH_TOLERANCE_SECONDS = 60L

        val default =
            BulkAvailabilityConfig(
                maxPois = DEFAULT_MAX_POIS,
                fanOutConcurrency = DEFAULT_FAN_OUT_CONCURRENCY,
                tolerance = Duration.ofHours(DEFAULT_TOLERANCE_HOURS),
                ipRateLimitPerMinute = DEFAULT_IP_RATE_LIMIT_PER_MINUTE,
                freshTolerance = Duration.ofSeconds(DEFAULT_FRESH_TOLERANCE_SECONDS),
            )

        fun fromConfig(config: ConfigSection): BulkAvailabilityConfig =
            BulkAvailabilityConfig(
                maxPois = config.value("max-pois")?.toInt() ?: default.maxPois,
                fanOutConcurrency = config.value("fan-out-concurrency")?.toInt() ?: default.fanOutConcurrency,
                tolerance = config.duration("tolerance", default.tolerance),
                ipRateLimitPerMinute =
                    config.value("ip-rate-limit-per-minute")?.toInt()
                        ?: default.ipRateLimitPerMinute,
                freshTolerance = config.duration("fresh-tolerance", default.freshTolerance),
            )
    }
}
