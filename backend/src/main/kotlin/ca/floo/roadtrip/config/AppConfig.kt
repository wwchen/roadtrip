package ca.floo.roadtrip.config

import java.time.Duration

data class AppConfig(
    val cache: ApiCacheConfig,
    val debug: DebugConfig,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig =
            AppConfig(
                cache = ApiCacheConfig.fromEnv(env),
                debug = DebugConfig.fromEnv(env),
            )
    }
}

data class DebugConfig(
    val enabled: Boolean = false,
) {
    companion object {
        private const val DEBUG_ENV_KEY = "ROADTRIP_DEBUG"

        fun fromEnv(env: Map<String, String> = System.getenv()): DebugConfig =
            DebugConfig(
                enabled = parseBoolean(env[DEBUG_ENV_KEY], DEBUG_ENV_KEY),
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
    ASPIRA_AVAILABILITY(
        namespace = "aspira_availability",
        envKey = "ROADTRIP_CACHE_ASPIRA_AVAILABILITY_TTL",
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

private val SIMPLE_DURATION = Regex("""^(\d+)(ms|s|m|h|d)?$""")

private fun parseBoolean(
    raw: String?,
    key: String,
): Boolean {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.isBlank()) return false
    return when (value) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("$key must be true or false")
    }
}

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
