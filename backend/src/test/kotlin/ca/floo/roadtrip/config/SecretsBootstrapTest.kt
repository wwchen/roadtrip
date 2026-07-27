package ca.floo.roadtrip.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretsBootstrapTest {
    @Test
    fun `hydrate copies mounted secrets into system properties`() {
        val dir = Files.createTempDirectory("secrets")
        Files.writeString(dir.resolve("mapbox_token"), "pk.example")

        withoutSystemProperty("MAPBOX_TOKEN") {
            val loaded = SecretsBootstrap.hydrate(dir)

            assertEquals(listOf("MAPBOX_TOKEN"), loaded)
            assertEquals("pk.example", System.getProperty("MAPBOX_TOKEN"))
        }
    }

    @Test
    fun `hydrate strips only the trailing newline`() {
        // Tokens and cookie headers contain spaces, quotes and '='; trimming
        // more than the file-format newline would corrupt them.
        val dir = Files.createTempDirectory("secrets")
        val value = " ak_bmsc=A1B2==; _abck=xy\"z "
        Files.writeString(dir.resolve("slack_bot_token"), "$value\n")

        withoutSystemProperty("SLACK_BOT_TOKEN") {
            SecretsBootstrap.hydrate(dir)

            assertEquals(value, System.getProperty("SLACK_BOT_TOKEN"))
        }
    }

    @Test
    fun `hydrate is a no-op when nothing is mounted`() {
        // The host-run backend (make run, and these tests) has no /run/secrets.
        assertEquals(emptyList(), SecretsBootstrap.hydrate(Path.of("/nonexistent/run/secrets")))
    }

    @Test
    fun `an environment variable wins over a mounted file`() {
        // So a one-off `MAPBOX_TOKEN=... make run` still overrides the vault.
        val dir = Files.createTempDirectory("secrets")
        val fromEnv = System.getenv().keys.firstOrNull() ?: return
        Files.writeString(dir.resolve(fromEnv.lowercase()), "from-file")

        withoutSystemProperty(fromEnv) {
            SecretsBootstrap.hydrate(dir)

            assertNull(System.getProperty(fromEnv))
        }
    }

    @Test
    fun `registry resource is on the classpath and declares required secrets`() {
        // Guards the processResources copy of secrets/registry.yaml: without it
        // boot validation silently has nothing to enforce.
        val required = SecretsBootstrap.requiredByProfile()

        assertTrue(required.isNotEmpty(), "registry resource parsed to nothing")
        assertContains(required.keys, "MAPBOX_TOKEN")
        assertContains(required.getValue("MAPBOX_TOKEN"), "prod")
        assertEquals(emptySet(), required.getValue("SLACK_BOT_TOKEN"))
    }

    @Test
    fun `boot fails listing every secret missing for the profile`() {
        val failure =
            assertFailsWith<MissingSecretsException> {
                SecretsBootstrap.run(
                    secretsDir = Path.of("/nonexistent"),
                    env = mapOf("ROADTRIP_PROFILE" to "prod"),
                )
            }

        // All at once: a misconfigured deploy should be one fix, not one
        // restart per missing secret.
        assertTrue(failure.missing.size > 1, "expected several missing, got ${failure.missing}")
        assertContains(failure.missing, "MAPBOX_TOKEN")
        assertContains(failure.message.orEmpty(), "manage.py set")
    }

    @Test
    fun `optional secrets do not fail the local profile`() {
        // local requires nothing: every secret-backed feature degrades, which
        // is what makes a credential-free checkout usable.
        SecretsBootstrap.run(
            secretsDir = Path.of("/nonexistent"),
            env = mapOf("ROADTRIP_PROFILE" to "local"),
        )
    }

    @Test
    fun `a blank value counts as missing`() {
        // ConfigSection.value() already maps "" to null, so a blank secret
        // disables its feature exactly as thoroughly as an absent one.
        val failure =
            assertFailsWith<MissingSecretsException> {
                SecretsBootstrap.run(
                    secretsDir = Path.of("/nonexistent"),
                    env = mapOf("ROADTRIP_PROFILE" to "prod", "MAPBOX_TOKEN" to "   "),
                )
            }

        assertContains(failure.missing, "MAPBOX_TOKEN")
    }

    private fun withoutSystemProperty(
        name: String,
        block: () -> Unit,
    ) {
        val previous = System.getProperty(name)
        System.clearProperty(name)
        try {
            block()
        } finally {
            if (previous == null) System.clearProperty(name) else System.setProperty(name, previous)
        }
    }
}
