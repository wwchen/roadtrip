package ca.floo.roadtrip.config

/**
 * Caps on the single-POI availability read. Its costlier bulk sibling has been
 * tunable since it shipped; this one was a route literal, which meant the
 * cheaper endpoint was the one an operator could not throttle.
 */
data class CampsiteAvailabilityConfig(
    val ipRateLimitPerMinute: Int,
) {
    init {
        require(ipRateLimitPerMinute >= 1) {
            "campsite ip-rate-limit-per-minute must be >= 1 (got $ipRateLimitPerMinute)"
        }
    }

    companion object {
        private const val DEFAULT_IP_RATE_LIMIT_PER_MINUTE = 30

        val default = CampsiteAvailabilityConfig(ipRateLimitPerMinute = DEFAULT_IP_RATE_LIMIT_PER_MINUTE)

        fun fromConfig(config: ConfigSection): CampsiteAvailabilityConfig =
            CampsiteAvailabilityConfig(
                ipRateLimitPerMinute =
                    config.value("ip-rate-limit-per-minute")?.toInt()
                        ?: default.ipRateLimitPerMinute,
            )
    }
}
