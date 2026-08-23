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
    val perPoiTimeout: Duration,
    val tolerance: Duration,
    val ipRateLimitPerMinute: Int,
) {
    init {
        require(maxPois >= 1) { "bulk max-pois must be >= 1 (got $maxPois)" }
        require(fanOutConcurrency >= 1) { "bulk fan-out-concurrency must be >= 1 (got $fanOutConcurrency)" }
        require(!perPoiTimeout.isZero && !perPoiTimeout.isNegative) {
            "bulk per-poi-timeout must be positive (got $perPoiTimeout)"
        }
        require(!tolerance.isNegative) { "bulk tolerance must not be negative (got $tolerance)" }
        require(ipRateLimitPerMinute >= 1) {
            "bulk ip-rate-limit-per-minute must be >= 1 (got $ipRateLimitPerMinute)"
        }
    }

    companion object {
        private const val DEFAULT_MAX_POIS = 50
        private const val DEFAULT_FAN_OUT_CONCURRENCY = 8
        private const val DEFAULT_PER_POI_TIMEOUT_SEC = 20L
        private const val DEFAULT_TOLERANCE_HOURS = 2L
        private const val DEFAULT_IP_RATE_LIMIT_PER_MINUTE = 10

        val default =
            BulkAvailabilityConfig(
                maxPois = DEFAULT_MAX_POIS,
                fanOutConcurrency = DEFAULT_FAN_OUT_CONCURRENCY,
                perPoiTimeout = Duration.ofSeconds(DEFAULT_PER_POI_TIMEOUT_SEC),
                tolerance = Duration.ofHours(DEFAULT_TOLERANCE_HOURS),
                ipRateLimitPerMinute = DEFAULT_IP_RATE_LIMIT_PER_MINUTE,
            )

        fun fromConfig(config: ConfigSection): BulkAvailabilityConfig =
            BulkAvailabilityConfig(
                maxPois = config.value("max-pois")?.toInt() ?: default.maxPois,
                fanOutConcurrency = config.value("fan-out-concurrency")?.toInt() ?: default.fanOutConcurrency,
                perPoiTimeout = config.duration("per-poi-timeout", default.perPoiTimeout),
                tolerance = config.duration("tolerance", default.tolerance),
                ipRateLimitPerMinute =
                    config.value("ip-rate-limit-per-minute")?.toInt()
                        ?: default.ipRateLimitPerMinute,
            )
    }
}
