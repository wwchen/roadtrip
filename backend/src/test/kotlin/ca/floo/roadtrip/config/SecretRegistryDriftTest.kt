package ca.floo.roadtrip.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The registry and `application*.yaml` must agree about which secrets the
 * backend consumes.
 *
 * This is the mechanism that replaces the sync scripts. Historically the list
 * of secret names lived in four hand-maintained places and drifted — at the
 * time this was written `CAMPFLARE_TOKEN` and `ROADTRIP_EMAIL_FROM` were read
 * by the backend but documented nowhere. Adding a `${'$'}{SOMETHING}` placeholder
 * without registering it, or registering a backend secret nothing reads, now
 * fails here instead of being discovered in production.
 */
class SecretRegistryDriftTest {
    private val repoRoot =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "secrets/registry.yaml").isFile }

    private val resourcesDir = File(repoRoot, "backend/src/main/resources")

    /**
     * Placeholders that are configuration rather than credentials: the runtime
     * profile, the port, and values Compose composes from non-secret parts.
     * Listed explicitly so a genuinely new secret can't hide among them.
     */
    private val nonSecretPlaceholders =
        setOf(
            "ENV",
            "PORT",
            "ROADTRIP_PROFILE",
            "POSTGRES_URL",
            "POSTGRES_USER",
            "ROADTRIP_AUTH_PROVIDER",
            "ROADTRIP_BOOTSTRAP_EMAILS",
            "ROADTRIP_EMAIL_FROM",
        )

    @Test
    fun `every backend secret placeholder is registered`() {
        val unregistered = placeholdersInApplicationYaml() - registeredBackendSecrets() - nonSecretPlaceholders

        assertEquals(
            emptySet(),
            unregistered,
            "application*.yaml reads these but secrets/registry.yaml does not declare them " +
                "with `consumers: [backend]`. Add them with ./secrets/manage.py add, or list " +
                "them in nonSecretPlaceholders if they are not credentials.",
        )
    }

    @Test
    fun `every registered backend secret is actually read`() {
        val unread = registeredBackendSecrets() - placeholdersInApplicationYaml()

        assertEquals(
            emptySet(),
            unread,
            "secrets/registry.yaml declares these as `consumers: [backend]` but no " +
                "application*.yaml placeholder reads them. Remove the consumer, or wire them up.",
        )
    }

    @Test
    fun `registry is non-trivial`() {
        // A parser that silently returned nothing would make both tests above
        // pass vacuously.
        assertTrue(registeredBackendSecrets().size > 5, "suspiciously few backend secrets")
    }

    private fun placeholdersInApplicationYaml(): Set<String> =
        resourcesDir
            .listFiles { file -> file.name.startsWith("application") && file.extension == "yaml" }
            .orEmpty()
            .flatMap { file ->
                Regex("""\$\{([A-Z][A-Z0-9_]*)""").findAll(file.readText()).map { it.groupValues[1] }
            }.toSet()

    /** Parsed from the source file, not the copied resource, so the copy is also exercised. */
    private fun registeredBackendSecrets(): Set<String> {
        val text = File(repoRoot, "secrets/registry.yaml").readText()
        val entries = mutableMapOf<String, String>()
        var current: String? = null
        for (line in text.lineSequence()) {
            Regex("""^([A-Z][A-Z0-9_]*):\s*$""").find(line)?.let { current = it.groupValues[1] }
            val name = current
            if (name != null && line.trimStart().startsWith("consumers:")) {
                entries[name] = line.substringAfter("consumers:").trim()
            }
        }
        return entries.filterValues { it.contains("backend") }.keys
    }
}
