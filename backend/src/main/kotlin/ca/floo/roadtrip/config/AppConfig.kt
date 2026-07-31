package ca.floo.roadtrip.config

import java.time.Duration

data class AppConfig(
    val admin: AdminConfig,
    val auth: AuthConfig?,
    val availability: AvailabilityConfig,
    val booking: BookingConfig,
    val cache: ApiCacheConfig,
    val campflare: CampflareConfig,
    val email: EmailConfig?,
    val readPathProviders: ReadPathProviderConfig,
    val route: RouteConfig,
    val secrets: SecretsConfig?,
    val slack: SlackConfig?,
    val grafana: GrafanaConfig?,
    val webApp: WebAppConfig?,
    val vendorRateLimit: VendorRateLimitConfig,
) {
    companion object {
        fun fromProperties(properties: Map<String, String>): AppConfig {
            val roadtrip = ConfigSection(properties).section("roadtrip")
            return AppConfig(
                admin = AdminConfig.fromConfig(roadtrip.section("admin")),
                auth = AuthConfig.fromConfig(roadtrip.section("auth")),
                availability = AvailabilityConfig.fromConfig(roadtrip.section("availability")),
                booking = BookingConfig.fromConfig(roadtrip.section("booking")),
                cache = ApiCacheConfig.fromConfig(roadtrip.section("cache")),
                campflare = CampflareConfig.fromConfig(roadtrip.section("campflare")),
                email = EmailConfig.fromConfig(roadtrip.section("email")),
                readPathProviders = ReadPathProviderConfig.fromConfig(roadtrip.section("read-path")),
                route = RouteConfig.fromConfig(roadtrip.section("route")),
                secrets = SecretsConfig.fromConfig(roadtrip.section("security")),
                slack = SlackConfig.fromConfig(roadtrip.section("slack")),
                grafana = GrafanaConfig.fromConfig(roadtrip.section("grafana")),
                webApp = WebAppConfig.fromConfig(roadtrip.section("web")),
                vendorRateLimit = VendorRateLimitConfig.fromConfig(roadtrip.section("vendor-rate-limit")),
            )
        }
    }
}

private val simpleDurationRegex = Regex("""^(\d+)(ms|s|m|h|d)?$""")

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
            ?: simpleDurationRegex
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
