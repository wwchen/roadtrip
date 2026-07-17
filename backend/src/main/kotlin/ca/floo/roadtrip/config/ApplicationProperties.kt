package ca.floo.roadtrip.config

import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.ConfigLoader

object ApplicationProperties {
    private const val PROFILE_ENV = "ROADTRIP_PROFILE"
    private const val DEFAULT_PROFILE = "local"
    private const val BASE_RESOURCE = "application.yaml"
    private const val RESOURCE_EXTENSION = "yaml"

    fun load(
        env: Map<String, String> = System.getenv(),
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: ApplicationProperties::class.java.classLoader,
        baseConfig: ApplicationConfig? = null,
    ): Map<String, String> {
        val profile =
            env[PROFILE_ENV]?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_PROFILE
        val profileResource = "application-$profile.$RESOURCE_EXTENSION"
        val base = baseConfig?.toMap() ?: loadResource(BASE_RESOURCE, classLoader)
        val overlay = loadResource(profileResource, classLoader)
        val values = linkedMapOf<String, String>()
        flattenMap(mergeMaps(base, overlay), prefix = "", values = values)
        return values
    }

    private fun loadResource(
        name: String,
        classLoader: ClassLoader,
    ): Map<String, Any?> {
        classLoader.getResource(name)
            ?: throw IllegalArgumentException("application config resource '$name' not found")
        return withContextClassLoader(classLoader) {
            ConfigLoader.load(name).toMap()
        }
    }

    private fun <T> withContextClassLoader(
        classLoader: ClassLoader,
        block: () -> T,
    ): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun mergeMaps(
        base: Map<String, Any?>,
        overlay: Map<String, Any?>,
    ): Map<String, Any?> {
        val merged = LinkedHashMap(base)
        overlay.forEach { (key, overlayValue) ->
            val baseValue = merged[key]
            merged[key] =
                if (baseValue is Map<*, *> && overlayValue is Map<*, *>) {
                    mergeMaps(baseValue.stringKeyMap(), overlayValue.stringKeyMap())
                } else {
                    overlayValue
                }
        }
        return merged
    }

    private fun Map<*, *>.stringKeyMap(): Map<String, Any?> = entries.associate { (key, value) -> key.toString() to value }

    private fun flattenMap(
        value: Any?,
        prefix: String,
        values: MutableMap<String, String>,
    ) {
        when (value) {
            is Map<*, *> ->
                value.forEach { (key, childValue) ->
                    val childPrefix = if (prefix.isEmpty()) key.toString() else "$prefix.$key"
                    flattenMap(childValue, childPrefix, values)
                }
            is List<*> -> values[prefix] = value.joinToString(",") { it?.toString().orEmpty() }
            null -> values[prefix] = ""
            else -> values[prefix] = value.toString()
        }
    }
}
