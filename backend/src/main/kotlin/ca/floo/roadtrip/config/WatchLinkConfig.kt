package ca.floo.roadtrip.config

import java.time.Duration

private const val TTL_KEY = "ttl"

/**
 * How long an alert email's magic link stays usable.
 *
 * Operationally tunable rather than a constant: the right number is a policy
 * call about how long a mailbox is trusted to hold a working link, and it wants
 * to differ between a sandbox and production without a rebuild. The default sits
 * in code so a missing config section keeps working behaviour.
 *
 * Deliberately shorter than the session TTL. A session lives behind a login and
 * a cookie the user can clear; a magic link lives in a mailbox forever, so the
 * window in which a forwarded or breached mailbox still controls a watch should
 * not be measured in months.
 */
data class WatchLinkConfig(
    val ttl: Duration,
) {
    companion object {
        private val defaultTtl: Duration = Duration.ofDays(30)

        /** For call sites with no config (tests, slim wiring). Production wires the real one. */
        val default = WatchLinkConfig(ttl = defaultTtl)

        fun fromConfig(config: ConfigSection): WatchLinkConfig = WatchLinkConfig(ttl = config.duration(TTL_KEY, defaultTtl))
    }
}
