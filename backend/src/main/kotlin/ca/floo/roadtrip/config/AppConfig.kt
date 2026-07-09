package ca.floo.roadtrip.config

import java.time.Duration

private const val CAMPFLARE_AVAILABILITY_MODE_ENV = "CAMPFLARE_AVAILABILITY_MODE"

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

enum class ApiCacheEntity(
    val namespace: String,
    val envKey: String,
    val defaultTtl: Duration,
) {
    ROUTE(
        namespace = "route",
        envKey = "ROADTRIP_CACHE_ROUTE_TTL",
        defaultTtl = Duration.ofMinutes(10),
    ),
    RECGOV_AVAILABILITY(
        namespace = "recgov_availability",
        envKey = "ROADTRIP_CACHE_RECGOV_AVAILABILITY_TTL",
        defaultTtl = Duration.ofHours(2),
    ),
    CAMPFLARE_AVAILABILITY(
        namespace = "campflare_availability",
        envKey = "ROADTRIP_CACHE_CAMPFLARE_AVAILABILITY_TTL",
        defaultTtl = Duration.ofHours(2),
    ),
    ASPIRA_AVAILABILITY(
        namespace = "aspira_availability",
        envKey = "ROADTRIP_CACHE_ASPIRA_AVAILABILITY_TTL",
        defaultTtl = Duration.ofHours(2),
    ),
    RESERVEAMERICA_AVAILABILITY(
        namespace = "reserveamerica_availability",
        envKey = "ROADTRIP_CACHE_RESERVEAMERICA_AVAILABILITY_TTL",
        defaultTtl = Duration.ofHours(2),
    ),
    RESERVECALIFORNIA_AVAILABILITY(
        namespace = "reservecalifornia_availability",
        envKey = "ROADTRIP_CACHE_RESERVECALIFORNIA_AVAILABILITY_TTL",
        defaultTtl = Duration.ofHours(2),
    ),
}

data class ApiCacheConfig(
    private val ttlByEntity: Map<ApiCacheEntity, Duration>,
) {
    fun ttlFor(entity: ApiCacheEntity): Duration = ttlByEntity[entity] ?: entity.defaultTtl

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ApiCacheConfig =
            ApiCacheConfig(
                ttlByEntity =
                    ApiCacheEntity
                        .entries
                        .associateWith { entity ->
                            parseDuration(
                                raw = env[entity.envKey],
                                default = entity.defaultTtl,
                                key = entity.envKey,
                            )
                        },
            )
    }
}

data class CampflareConfig(
    val apiKey: String?,
    val apiBaseUrl: String,
    val availabilityMode: CampflareAvailabilityMode,
) {
    val usesCampflareAvailability: Boolean
        get() = availabilityMode.usesCampflareAvailability(apiKeyConfigured = apiKey != null)

    companion object {
        private const val DEFAULT_API_BASE_URL = "https://api.campflare.com/v2"

        fun fromEnv(env: Map<String, String> = System.getenv()): CampflareConfig =
            CampflareConfig(
                apiKey =
                    env["CAMPFLARE_API_KEY"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: env["CAMPFLARE_TOKEN"]
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() },
                apiBaseUrl =
                    env["CAMPFLARE_API_BASE"]
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: DEFAULT_API_BASE_URL,
                availabilityMode = CampflareAvailabilityMode.parse(env[CAMPFLARE_AVAILABILITY_MODE_ENV]),
            )
    }
}

enum class CampflareAvailabilityMode {
    AUTO,
    CAMPFLARE,
    RECGOV,
    ;

    fun usesCampflareAvailability(apiKeyConfigured: Boolean): Boolean =
        when (this) {
            AUTO -> apiKeyConfigured
            CAMPFLARE -> true
            RECGOV -> false
        }

    companion object {
        fun parse(raw: String?): CampflareAvailabilityMode {
            val value = raw?.trim()?.lowercase().orEmpty()
            if (value.isBlank()) return AUTO
            return when (value) {
                "auto" -> AUTO
                "campflare" -> CAMPFLARE
                "recgov" -> RECGOV
                else -> throw IllegalArgumentException("$CAMPFLARE_AVAILABILITY_MODE_ENV must be one of auto, campflare, recgov")
            }
        }
    }
}

private val SIMPLE_DURATION = Regex("""^(\d+)(ms|s|m|h|d)?$""")

private fun parseDuration(
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
