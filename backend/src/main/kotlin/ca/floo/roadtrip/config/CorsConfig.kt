package ca.floo.roadtrip.config

/**
 * Cross-origin config for browser callers served from a different origin than
 * the API. The one real need today is local dev: Grafana (`:3000`) firing a
 * one-click action POST at the backend (`:8765`) is cross-origin, so the
 * browser needs an `Access-Control-Allow-Origin` on the response. In prod
 * Grafana (`/dash`) and the API (`/api`) share one host, so this stays unset
 * and no CORS headers are emitted.
 *
 * [fromEnv] reads [ORIGINS_ENV] (comma-separated list of scheme+host[:port],
 * e.g. `http://127.0.0.1:3000`) and returns null when unset/blank — a
 * first-class "same-origin only" state, not an error. There is deliberately no
 * baked-in default origin: an env-specific host in the binary is exactly the
 * config-in-code leak [GrafanaConfig] and [SlackConfig] avoid.
 */
data class CorsConfig(
    val allowedOrigins: List<String>,
) {
    companion object {
        const val ORIGINS_ENV = "CORS_ALLOWED_ORIGINS"

        fun fromEnv(env: Map<String, String> = System.getenv()): CorsConfig? {
            val origins =
                env[ORIGINS_ENV]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            if (origins.isEmpty()) return null
            return CorsConfig(allowedOrigins = origins)
        }
    }
}
