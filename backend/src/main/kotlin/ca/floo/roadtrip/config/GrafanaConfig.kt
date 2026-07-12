package ca.floo.roadtrip.config

/**
 * Where our Grafana lives, so alert messages can deep-link a dashboard.
 *
 * [fromProperties] returns null when unset/blank — a first-class "no dashboard links"
 * state (the alert still sends, just without the Grafana lines). There is
 * deliberately no code-level default host: a hardcoded prod URL would be
 * environment-specific config leaking into the binary.
 *
 * [rootUrl] is stored with any trailing slash stripped so callers build
 * `"$rootUrl/d/…"` cleanly.
 */
data class GrafanaConfig(
    val rootUrl: String,
) {
    companion object {
        fun fromProperties(properties: Map<String, String>): GrafanaConfig? =
            fromConfig(ConfigSection(properties).section("roadtrip.grafana"))

        fun fromConfig(config: ConfigSection): GrafanaConfig? {
            val raw = config.value("root-url") ?: return null
            return GrafanaConfig(rootUrl = raw.trimEnd('/'))
        }
    }
}
