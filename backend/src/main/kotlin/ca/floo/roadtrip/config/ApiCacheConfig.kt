package ca.floo.roadtrip.config

import java.time.Duration

data class ApiCacheConfig(
    private val ttlByEntity: Map<ApiCacheEntity, Duration>,
) {
    fun ttlFor(entity: ApiCacheEntity): Duration = ttlByEntity[entity] ?: entity.defaultTtl

    companion object {
        fun fromProperties(properties: Map<String, String>): ApiCacheConfig =
            fromConfig(ConfigSection(properties).section("roadtrip.cache"))

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
