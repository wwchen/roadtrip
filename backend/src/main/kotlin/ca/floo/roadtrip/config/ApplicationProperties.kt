package ca.floo.roadtrip.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar

object ApplicationProperties {
    private const val PROFILE_ENV = "ROADTRIP_PROFILE"
    private const val DEFAULT_PROFILE = "local"
    private const val BASE_YAML_RESOURCE = "application.yml"
    private const val YAML_EXTENSION = "yml"
    private val PLACEHOLDER = Regex("""\$\{([A-Za-z_][A-Za-z0-9_.-]*)}""")
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    fun load(
        env: Map<String, String> = System.getenv(),
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader ?: ApplicationProperties::class.java.classLoader,
    ): Map<String, String> {
        val profile =
            env[PROFILE_ENV]?.trim()?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_PROFILE
        val values = linkedMapOf<String, String>()
        values.putAll(loadYamlResource(BASE_YAML_RESOURCE, classLoader))
        values.putAll(loadYamlResource("application-$profile.$YAML_EXTENSION", classLoader))
        return resolvePlaceholders(values, env)
    }

    private fun loadYamlResource(
        name: String,
        classLoader: ClassLoader,
    ): Map<String, String> {
        val stream =
            classLoader.getResourceAsStream(name)
                ?: throw IllegalArgumentException("application config resource '$name' not found")
        val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return flattenYaml(content)
    }

    private fun flattenYaml(content: String): Map<String, String> {
        if (content.isBlank()) return emptyMap()
        val values = linkedMapOf<String, String>()
        flattenYamlNode(node = yaml.parseToYamlNode(content), prefix = "", values = values)
        return values
    }

    private fun flattenYamlNode(
        node: YamlNode,
        prefix: String,
        values: MutableMap<String, String>,
    ) {
        when (node) {
            is YamlMap ->
                node.entries.forEach { (key, value) ->
                    val childPrefix = if (prefix.isEmpty()) key.content else "$prefix.${key.content}"
                    flattenYamlNode(value, childPrefix, values)
                }
            is YamlList -> values[prefix] = node.items.joinToString(",") { scalarListValue(it) }
            is YamlScalar -> values[prefix] = node.content
            is YamlNull -> values[prefix] = ""
            else -> values[prefix] = node.contentToString()
        }
    }

    private fun scalarListValue(node: YamlNode): String =
        when (node) {
            is YamlScalar -> node.content
            is YamlNull -> ""
            else -> node.contentToString()
        }

    private fun resolvePlaceholders(
        values: Map<String, String>,
        env: Map<String, String>,
    ): Map<String, String> =
        values.keys.associateWith { key ->
            resolveValue(key = key, values = values, env = env, seen = emptySet())
        }

    private fun resolveValue(
        key: String,
        values: Map<String, String>,
        env: Map<String, String>,
        seen: Set<String>,
    ): String {
        val raw = values[key].orEmpty()
        return PLACEHOLDER.replace(raw) { match ->
            val ref = match.groupValues[1]
            when {
                ref in env -> env.getValue(ref)
                ref == key || ref in seen -> ""
                ref in values -> resolveValue(key = ref, values = values, env = env, seen = seen + key)
                else -> ""
            }
        }
    }
}
