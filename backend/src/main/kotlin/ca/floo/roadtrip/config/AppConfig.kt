package ca.floo.roadtrip.config

import java.time.Duration

data class AppConfig(
    val availability: AvailabilityConfig,
    val cache: ApiCacheConfig,
    val campflare: CampflareConfig,
    val dispatch: DispatchConfig,
    val readPathProviders: ReadPathProviderConfig,
    val slack: SlackConfig?,
    val grafana: GrafanaConfig?,
    val webApp: WebAppConfig?,
    val vendorRateLimit: VendorRateLimitConfig,
) {
    companion object {
        fun fromProperties(properties: Map<String, String>): AppConfig {
            val roadtrip = ConfigSection(properties).section("roadtrip")
            return AppConfig(
                availability = AvailabilityConfig.fromConfig(roadtrip.section("availability")),
                cache = ApiCacheConfig.fromConfig(roadtrip.section("cache")),
                campflare = CampflareConfig.fromConfig(roadtrip.section("campflare")),
                dispatch = DispatchConfig.fromConfig(roadtrip.section("dispatch")),
                readPathProviders = ReadPathProviderConfig.fromConfig(roadtrip.section("read-path")),
                slack = SlackConfig.fromConfig(roadtrip.section("slack")),
                grafana = GrafanaConfig.fromConfig(roadtrip.section("grafana")),
                webApp = WebAppConfig.fromConfig(roadtrip.section("web")),
                vendorRateLimit = VendorRateLimitConfig.fromConfig(roadtrip.section("vendor-rate-limit")),
            )
        }
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
    return parseDurationValue(value, key)
}

internal fun parseRequiredDuration(
    raw: String?,
    key: String,
): Duration {
    val value = raw?.trim().orEmpty()
    require(value.isNotBlank()) { "$key is required" }
    return parseDurationValue(value, key)
}

private fun parseDurationValue(
    value: String,
    key: String,
): Duration {
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
