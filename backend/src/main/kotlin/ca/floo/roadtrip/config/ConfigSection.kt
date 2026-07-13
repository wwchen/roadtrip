package ca.floo.roadtrip.config

import java.time.Duration

data class ConfigSection(
    private val values: Map<String, String>,
    val prefix: String = "",
) {
    fun section(name: String): ConfigSection = ConfigSection(values = values, prefix = key(name))

    fun value(name: String): String? = rawValue(name)?.trim()?.takeIf { it.isNotEmpty() }

    fun rawValue(name: String): String? = values[key(name)]

    fun valueOrDefault(
        name: String,
        defaultValue: String,
    ): String = value(name) ?: defaultValue

    fun duration(
        name: String,
        default: Duration,
    ): Duration =
        parseDuration(
            raw = value(name),
            default = default,
            key = key(name),
        )

    fun requiredDuration(name: String): Duration =
        parseRequiredDuration(
            raw = value(name),
            key = key(name),
        )

    fun csvSet(name: String): Set<String> =
        value(name)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    fun absoluteKeys(): Set<String> = values.keys

    fun relativeKey(absoluteKey: String): String? {
        if (prefix.isBlank()) return absoluteKey
        val dottedPrefix = "$prefix."
        return absoluteKey.removePrefix(dottedPrefix).takeIf { it != absoluteKey }
    }

    fun key(name: String): String =
        when {
            prefix.isBlank() -> name
            name.isBlank() -> prefix
            else -> "$prefix.$name"
        }
}
