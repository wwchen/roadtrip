package ca.floo.roadtrip.config

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import java.time.Duration

class ApiCacheConfig(
    private val ttlByEntity: Map<ApiCacheEntity, Duration>,
) {
    fun ttlFor(entity: ApiCacheEntity): Duration = ttlByEntity[entity] ?: entity.defaultTtl

    fun availabilityTtl(provider: BookingProvider): Duration = ttlFor(ApiCacheEntity.availability(provider))

    companion object {
        fun fromConfig(config: ConfigSection): ApiCacheConfig =
            ApiCacheConfig(
                ttlByEntity =
                    ApiCacheEntity
                        .entries
                        .associateWith { entity ->
                            config.duration(entity.configKey, entity.defaultTtl)
                        },
            )
    }
}
