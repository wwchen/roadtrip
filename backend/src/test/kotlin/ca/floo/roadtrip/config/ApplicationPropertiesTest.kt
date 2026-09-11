package ca.floo.roadtrip.config

import ca.floo.roadtrip.fixtures.configResourceClassLoader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationPropertiesTest {
    @Test
    fun `load overlays selected profile yaml after base yaml`() {
        val props =
            withSystemProperties("SECRET_VALUE" to "from-env") {
                ApplicationProperties.load(
                    env = emptyMap(),
                    classLoader =
                        configResourceClassLoader(
                            "application.yaml" to
                                """
                                shared: base-yaml
                                base-only: base
                                secret: ${'$'}{SECRET_VALUE}
                                """.trimIndent(),
                            "application-local.yaml" to
                                """
                                profile-only: profile-yaml
                                shared: profile-yaml
                                """.trimIndent(),
                        ),
                )
            }

        assertEquals("base", props["base-only"])
        assertEquals("from-env", props["secret"])
        assertEquals("profile-yaml", props["profile-only"])
        assertEquals("profile-yaml", props["shared"])
    }

    @Test
    fun `load resolves property placeholders from environment and other properties`() {
        val props =
            withSystemProperties("SECRET_VALUE" to "from-env") {
                ApplicationProperties.load(
                    env = emptyMap(),
                    classLoader =
                        configResourceClassLoader(
                            "application.yaml" to
                                """
                                direct: ${'$'}{SECRET_VALUE}
                                missing: ${'$'}{DOES_NOT_EXIST:}
                                defaulted: ${'$'}{DOES_NOT_EXIST:fallback}
                                emptyDefault: ${'$'}{DOES_NOT_EXIST:}
                                chained: ${'$'}{direct}
                                list:
                                  - one
                                  - two
                                """.trimIndent(),
                            "application-local.yaml" to "{}",
                        ),
                )
            }

        assertEquals("from-env", props["direct"])
        assertEquals("", props["missing"])
        assertEquals("fallback", props["defaulted"])
        assertEquals("", props["emptyDefault"])
        assertEquals("from-env", props["chained"])
        assertEquals("one,two", props["list"])
    }

    @Test
    fun `load fails when selected profile resource is missing`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                ApplicationProperties.load(
                    env = mapOf("ROADTRIP_PROFILE" to "typo"),
                    classLoader =
                        configResourceClassLoader(
                            "application.yaml" to "roadtrip:\n  static-dir: .",
                        ),
                )
            }

        assertEquals("application config resource 'application-typo.yaml' not found", err.message)
    }

    private fun <T> withSystemProperties(
        vararg values: Pair<String, String>,
        block: () -> T,
    ): T {
        val previousValues = values.associate { (key, _) -> key to System.getProperty(key) }
        values.forEach { (key, value) -> System.setProperty(key, value) }
        return try {
            block()
        } finally {
            values.forEach { (key, _) ->
                val previous = previousValues[key]
                if (previous == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, previous)
                }
            }
        }
    }
}
