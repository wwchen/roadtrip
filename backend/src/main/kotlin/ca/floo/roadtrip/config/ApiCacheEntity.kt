package ca.floo.roadtrip.config

import java.time.Duration

enum class ApiCacheEntity(
    val namespace: String,
    val configKey: String,
    val defaultTtl: Duration,
) {
    ROUTE(
        namespace = "route",
        configKey = "route.ttl",
        defaultTtl = Duration.ofMinutes(10),
    ),
    RECGOV_AVAILABILITY(
        namespace = "recgov_availability",
        configKey = "recgov-availability.ttl",
        defaultTtl = Duration.ofHours(2),
    ),
    CAMPFLARE_AVAILABILITY(
        namespace = "campflare_availability",
        configKey = "campflare-availability.ttl",
        defaultTtl = Duration.ofHours(2),
    ),
    ASPIRA_AVAILABILITY(
        namespace = "aspira_availability",
        configKey = "aspira-availability.ttl",
        defaultTtl = Duration.ofHours(2),
    ),
    RESERVEAMERICA_AVAILABILITY(
        namespace = "reserveamerica_availability",
        configKey = "reserveamerica-availability.ttl",
        defaultTtl = Duration.ofHours(2),
    ),
    RESERVECALIFORNIA_AVAILABILITY(
        namespace = "reservecalifornia_availability",
        configKey = "reservecalifornia-availability.ttl",
        defaultTtl = Duration.ofHours(2),
    ),
}
