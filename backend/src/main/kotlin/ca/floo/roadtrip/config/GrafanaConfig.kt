package ca.floo.roadtrip.config

/**
 * Where our Grafana lives, so alert messages can deep-link a dashboard. Read
 * from [ROOT_URL_ENV] — the same var the Grafana container uses for
 * `GF_SERVER_ROOT_URL` — so the backend and Grafana share one source of truth
 * for the host. The environment-specific value (localhost in dev,
 * `roadtrip.floo.ca` in prod) lives in deploy config (compose), never here.
 *
 * [fromEnv] returns null when unset/blank — a first-class "no dashboard links"
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
        const val ROOT_URL_ENV = "GRAFANA_ROOT_URL"

        fun fromEnv(env: Map<String, String> = System.getenv()): GrafanaConfig? {
            val raw = env[ROOT_URL_ENV]?.trim()?.ifEmpty { null } ?: return null
            return GrafanaConfig(rootUrl = raw.trimEnd('/'))
        }
    }
}
