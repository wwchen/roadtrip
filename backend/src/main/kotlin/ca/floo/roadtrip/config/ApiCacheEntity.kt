package ca.floo.roadtrip.config

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import java.time.Duration

data class ApiCacheEntity(
    val namespace: String,
    val configKey: String,
    val defaultTtl: Duration,
) {
    @Suppress("ObjectPropertyNaming")
    companion object {
        private val DEFAULT_ROUTE_TTL: Duration = Duration.ofMinutes(10)
        private val DEFAULT_AVAILABILITY_TTL: Duration = Duration.ofHours(2)

        val ROUTE: ApiCacheEntity =
            ApiCacheEntity(
                namespace = "route",
                configKey = "route.ttl",
                defaultTtl = DEFAULT_ROUTE_TTL,
            )

        val entries: List<ApiCacheEntity> = listOf(ROUTE) + BookingProvider.entries.map(::availability)

        fun availability(provider: BookingProvider): ApiCacheEntity =
            ApiCacheEntity(
                namespace = "${provider.id}_availability",
                configKey = "${provider.id}-availability.ttl",
                defaultTtl = DEFAULT_AVAILABILITY_TTL,
            )
    }
}
