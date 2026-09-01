package ca.floo.roadtrip.config

import ca.floo.roadtrip.service.scheduler.DEFAULT_MAX_KEEP_WARM_PROFILES
import java.time.Duration

data class BookingConfig(
    val recgovAtc: RecGovAtcConfig,
    /**
     * How old the last observation of a night may be and still gate a direct
     * add-to-cart.
     *
     * Defaults to the poller's own default cadence: a cell older than one poll
     * cycle means the poller is behind or the site fell out of coverage, and
     * either way we are guessing. Tunable because the right answer follows the
     * cadence, which is itself tunable.
     */
    val freshnessMaxAge: Duration = defaultFreshnessMaxAge,
    /**
     * How many profiles the keepalive job may ask the companion to keep warm.
     *
     * A bound on what we *ask* for, not on what the companion launches — armed
     * profiles are exempt from its own browser cap, so an unbounded set here
     * would push that overflow onto it.
     */
    val maxKeepWarmProfiles: Int = DEFAULT_MAX_KEEP_WARM_PROFILES,
) {
    init {
        require(maxKeepWarmProfiles > 0) { "booking maxKeepWarmProfiles must be positive" }
        require(!freshnessMaxAge.isZero && !freshnessMaxAge.isNegative) { "booking freshnessMaxAge must be positive" }
    }

    companion object {
        /** The poller's default cadence. See `availability.poller.default-cadence`. */
        private val defaultFreshnessMaxAge: Duration = Duration.ofMinutes(5)

        fun fromConfig(config: ConfigSection): BookingConfig =
            BookingConfig(
                recgovAtc = RecGovAtcConfig.fromConfig(config.section("recgov-atc")),
                freshnessMaxAge = config.duration("freshness-max-age", defaultFreshnessMaxAge),
                maxKeepWarmProfiles =
                    config.value("max-keep-warm-profiles")?.toIntOrNull() ?: DEFAULT_MAX_KEEP_WARM_PROFILES,
            )
    }
}

data class RecGovAtcConfig(
    val companionBaseUrl: String?,
    val companionTimeout: Duration,
    // Shared secret the companion requires on every route. Blank means the
    // companion answers 503 and ATC cannot run.
    val companionApiToken: String? = null,
    /**
     * How often the keepalive job re-arms and refreshes the profiles backing an
     * active `atc` watch. Tunable because it trades companion load against how
     * stale a session can get before a 3 a.m. firing has to pay for a re-login.
     */
    val keepaliveInterval: Duration = defaultKeepaliveInterval,
    /**
     * The budget for the checks that run *before* a hold: the session
     * preflight and the one unattended re-login.
     *
     * Much shorter than [companionTimeout], which sizes a full browser-driven
     * cart run. These two are cheap when the profile is warm, and an ATC racing
     * other users cannot afford to spend the ATC budget twice over before it
     * even starts — by then the site is gone.
     */
    val fireTimeout: Duration = defaultFireTimeout,
) {
    init {
        require(companionTimeout.isPositive()) { "recgov ATC companionTimeout must be positive" }
        require(keepaliveInterval.isPositive()) { "recgov ATC keepaliveInterval must be positive" }
        require(fireTimeout.isPositive()) { "recgov ATC fireTimeout must be positive" }
    }

    val companionEnabled: Boolean get() = companionBaseUrl != null

    companion object {
        private val defaultCompanionTimeout: Duration = Duration.ofSeconds(180)
        private val defaultKeepaliveInterval: Duration = Duration.ofMinutes(15)
        private val defaultFireTimeout: Duration = Duration.ofSeconds(30)

        fun fromConfig(config: ConfigSection): RecGovAtcConfig =
            RecGovAtcConfig(
                companionBaseUrl = config.value("companion-base-url")?.trimEnd('/'),
                companionTimeout = config.duration("companion-timeout", defaultCompanionTimeout),
                companionApiToken = config.value("companion-api-token"),
                keepaliveInterval = config.duration("keepalive-interval", defaultKeepaliveInterval),
                fireTimeout = config.duration("fire-timeout", defaultFireTimeout),
            )
    }
}

private fun Duration.isPositive(): Boolean = !isZero && !isNegative
