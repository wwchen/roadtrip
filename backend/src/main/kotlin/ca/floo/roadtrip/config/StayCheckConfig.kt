package ca.floo.roadtrip.config

import java.time.Duration

/**
 * Caps and timings for the stay check. It is the one read that can turn a single
 * request into many upstream calls, so every bound here is operationally tunable.
 */
data class StayCheckConfig(
    val maxPois: Int,
    /**
     * How many POIs of **one** check are read at a time — its vendor calls in
     * flight, paced so a [maxPois]-POI check does not reach every provider at
     * once, and so [perPoiTimeout] clocks a read that has actually started.
     * Per check, not per process: concurrent checks each get their own bound,
     * and nothing caps how many run at once.
     */
    val fanOutConcurrency: Int,
    /**
     * How old a stored observation may be and still answer, rather than asking the
     * provider again: the default age, and the ceiling of what a request may ask for.
     */
    val defaultMaxAge: Duration,
    /**
     * How many campgrounds one caller may ask about per minute. Charged per POI,
     * not per request: one request fans out to an upstream
     * call per POI, so a request count would let a caller's upstream load grow with
     * the request size. At least [maxPois], so a request the cap allows can pass.
     */
    val ipPoiLimitPerMinute: Int,
    /**
     * The floor of the age a request may ask for (`max_age_seconds`, as the map's
     * Check again does): a shorter one is raised to it. Short rather than zero, so a
     * double-click does not ask every provider twice; never longer than [defaultMaxAge],
     * which it defaults down to.
     */
    val minMaxAge: Duration = minOf(defaultMaxAge, Duration.ofSeconds(DEFAULT_MIN_MAX_AGE_SECONDS)),
    /**
     * How long one campground of a streamed stay check may take before it answers
     * `timeout`. The stream can only finish when every campground has answered,
     * and a provider can stall indefinitely.
     */
    val perPoiTimeout: Duration = Duration.ofSeconds(DEFAULT_PER_POI_TIMEOUT_SECONDS),
    /**
     * How long a whole streamed stay check may take. Past it, every campground yet
     * to answer answers `timeout` and the stream ends — without it, a provider
     * outage across the view would keep a check open for a per-POI timeout per
     * fan-out round.
     */
    val streamTimeout: Duration = Duration.ofSeconds(DEFAULT_STREAM_TIMEOUT_SECONDS),
    /**
     * How often a stream with nothing to send writes a keep-alive comment, so a
     * proxy in front does not take a quiet stream for a dead one — and so a
     * client can: at most [MAX_STREAM_HEARTBEAT_SECONDS].
     */
    val streamHeartbeat: Duration = Duration.ofSeconds(DEFAULT_STREAM_HEARTBEAT_SECONDS),
) {
    init {
        require(maxPois >= 1) { "stays max-pois must be >= 1 (got $maxPois)" }
        require(fanOutConcurrency >= 1) { "stays fan-out-concurrency must be >= 1 (got $fanOutConcurrency)" }
        require(!defaultMaxAge.isNegative) { "stays default-max-age must not be negative (got $defaultMaxAge)" }
        require(ipPoiLimitPerMinute >= maxPois) {
            "stays ip-poi-limit-per-minute must be at least max-pois ($maxPois) (got $ipPoiLimitPerMinute)"
        }
        require(!minMaxAge.isNegative && minMaxAge <= defaultMaxAge) {
            "stays min-max-age must be between 0 and default-max-age ($defaultMaxAge) (got $minMaxAge)"
        }
        require(perPoiTimeout.isPositive) { "stays per-poi-timeout must be positive (got $perPoiTimeout)" }
        require(streamTimeout.isPositive) { "stays stream-timeout must be positive (got $streamTimeout)" }
        require(streamHeartbeat.isPositive && streamHeartbeat <= Duration.ofSeconds(MAX_STREAM_HEARTBEAT_SECONDS)) {
            "stays stream-heartbeat must be between 0 and ${MAX_STREAM_HEARTBEAT_SECONDS}s (got $streamHeartbeat)"
        }
    }

    companion object {
        private const val DEFAULT_MAX_POIS = 50

        private const val DEFAULT_FAN_OUT_CONCURRENCY = 3
        private const val DEFAULT_MAX_AGE_HOURS = 2L

        /** Three full checks a minute — near what the batched check allowed (10 requests of ~13 POIs). */
        private const val DEFAULT_IP_POI_LIMIT_PER_MINUTE = 150
        private const val DEFAULT_MIN_MAX_AGE_SECONDS = 60L
        private const val DEFAULT_PER_POI_TIMEOUT_SECONDS = 45L
        private const val DEFAULT_STREAM_TIMEOUT_SECONDS = 120L
        private const val DEFAULT_STREAM_HEARTBEAT_SECONDS = 15L

        /**
         * The longest a stream may go without writing anything. A client takes a
         * stream silent for a minute as a lost connection (the map's check,
         * `CHECK_IDLE_TIMEOUT_MS` in `frontend/src/features/trip/stay-check.ts`),
         * so the heartbeat has to beat well inside that — and the providers' pace
         * stays the backend's own business, bounded by the timeouts above.
         */
        const val MAX_STREAM_HEARTBEAT_SECONDS = 20L

        val default =
            StayCheckConfig(
                maxPois = DEFAULT_MAX_POIS,
                fanOutConcurrency = DEFAULT_FAN_OUT_CONCURRENCY,
                defaultMaxAge = Duration.ofHours(DEFAULT_MAX_AGE_HOURS),
                ipPoiLimitPerMinute = DEFAULT_IP_POI_LIMIT_PER_MINUTE,
                minMaxAge = Duration.ofSeconds(DEFAULT_MIN_MAX_AGE_SECONDS),
            )

        fun fromConfig(config: ConfigSection): StayCheckConfig =
            StayCheckConfig(
                maxPois = config.value("max-pois")?.toInt() ?: default.maxPois,
                fanOutConcurrency = config.value("fan-out-concurrency")?.toInt() ?: default.fanOutConcurrency,
                defaultMaxAge = config.duration("default-max-age", default.defaultMaxAge),
                ipPoiLimitPerMinute =
                    config.value("ip-poi-limit-per-minute")?.toInt()
                        ?: default.ipPoiLimitPerMinute,
                minMaxAge = config.duration("min-max-age", default.minMaxAge),
                perPoiTimeout = config.duration("per-poi-timeout", default.perPoiTimeout),
                streamTimeout = config.duration("stream-timeout", default.streamTimeout),
                streamHeartbeat = config.duration("stream-heartbeat", default.streamHeartbeat),
            )
    }
}
