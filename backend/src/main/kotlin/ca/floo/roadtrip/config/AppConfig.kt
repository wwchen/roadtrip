package ca.floo.roadtrip.config

import java.time.Duration

data class AppConfig(
    val cache: ApiCacheConfig,
    val campflare: CampflareConfig,
    /** Slack alerting config, or null when unconfigured (alerts disabled). */
    val slack: SlackConfig?,
    /** Grafana host for dashboard deep links in alerts, or null when unset
     *  (alerts omit the dashboard links). */
    val grafana: GrafanaConfig?,
    /** Public web app host for POI map deep links in alerts, or null when unset
     *  (alerts omit the map links). */
    val webApp: WebAppConfig?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig =
            AppConfig(
                cache = ApiCacheConfig.fromEnv(env),
                campflare = CampflareConfig.fromEnv(env),
                slack = SlackConfig.fromEnv(env),
                grafana = GrafanaConfig.fromEnv(env),
                webApp = WebAppConfig.fromEnv(env),
            )
    }
}

private val SIMPLE_DURATION = Regex("""^(\d+)(ms|s|m|h|d)?$""")

internal fun parseDuration(
    raw: String?,
    default: Duration,
    key: String,
): Duration {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return default

    val parsed =
        runCatching { Duration.parse(value) }.getOrNull()
            ?: SIMPLE_DURATION
                .matchEntire(value.lowercase())
                ?.let { match ->
                    val amount = match.groupValues[1].toLong()
                    when (match.groupValues[2]) {
                        "ms" -> Duration.ofMillis(amount)
                        "", "s" -> Duration.ofSeconds(amount)
                        "m" -> Duration.ofMinutes(amount)
                        "h" -> Duration.ofHours(amount)
                        "d" -> Duration.ofDays(amount)
                        else -> null
                    }
                }
            ?: throw IllegalArgumentException("$key must be an ISO-8601 duration or a number with ms/s/m/h/d")

    require(!parsed.isZero && !parsed.isNegative) { "$key must be positive" }
    return parsed
}
