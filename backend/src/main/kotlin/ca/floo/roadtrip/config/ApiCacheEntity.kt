package ca.floo.roadtrip.config

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import java.time.Duration
import java.util.Objects

// defaultTtl is deliberately outside equality: ttlByEntity keys on identity, so a
// per-provider default must not make a lookup miss.
class ApiCacheEntity(
    val namespace: String,
    val configKey: String,
    val defaultTtl: Duration,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ApiCacheEntity && namespace == other.namespace && configKey == other.configKey)

    override fun hashCode(): Int = Objects.hash(namespace, configKey)

    override fun toString(): String = "ApiCacheEntity(namespace=$namespace, configKey=$configKey)"

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
