package ca.floo.roadtrip.config

/**
 * Where our public web app lives, so alert messages can deep-link a POI on the
 * map (`"$rootUrl/?poi=<id>"`). The app is served at the root of the same host
 * Grafana's `/dash/` sits under, but its origin is kept as its own config rather
 * than derived from [GrafanaConfig] — a POI link must not depend on Grafana
 * being configured or on its path layout.
 *
 * [fromConfig] returns null when unset/blank — a first-class "no map links" state
 * (the alert still sends, just without the POI lines). There is deliberately no
 * code-level default host: a hardcoded prod URL would be environment-specific
 * config leaking into the binary.
 *
 * [rootUrl] is stored with any trailing slash stripped so callers build
 * `"$rootUrl/?poi=…"` cleanly.
 */
data class WebAppConfig(
    val rootUrl: String,
) {
    companion object {
        fun fromConfig(config: ConfigSection): WebAppConfig? {
            val raw = config.value("root-url") ?: return null
            return WebAppConfig(rootUrl = raw.trimEnd('/'))
        }
    }
}
