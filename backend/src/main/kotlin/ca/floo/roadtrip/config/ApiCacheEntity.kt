package ca.floo.roadtrip.config

import java.time.Duration

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
