package ca.floo.roadtrip.config

import java.time.Duration

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
