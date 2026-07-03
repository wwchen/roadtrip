package ca.floo.roadtrip.config

/**
 * Where our Grafana lives, so alert messages can deep-link a dashboard. Read
 * from [ROOT_URL_ENV] — the same var the Grafana container uses for
 * `GF_SERVER_ROOT_URL` (`http://localhost:3000/dash` in local compose,
 * `https://roadtrip.floo.ca/dash` in prod) — so the backend and Grafana agree
 * on the host without a second source of truth.
 *
 * [rootUrl] is stored with any trailing slash stripped so callers build
 * `"$rootUrl/d/…"` cleanly. Always present (defaults to prod); a link is always
 * useful, so there is no disabled state.
 */
data class GrafanaConfig(
    val rootUrl: String,
) {
    companion object {
        const val ROOT_URL_ENV = "GRAFANA_ROOT_URL"
        const val DEFAULT_ROOT_URL = "https://roadtrip.floo.ca/dash"

        fun fromEnv(env: Map<String, String> = System.getenv()): GrafanaConfig {
            val raw = env[ROOT_URL_ENV]?.trim()?.ifEmpty { null } ?: DEFAULT_ROOT_URL
            return GrafanaConfig(rootUrl = raw.trimEnd('/'))
        }
    }
}
