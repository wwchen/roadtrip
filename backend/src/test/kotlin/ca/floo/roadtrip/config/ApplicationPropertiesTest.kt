package ca.floo.roadtrip.config

import io.ktor.server.config.ConfigLoader
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationPropertiesTest {
    @Test
    fun `ktor application yaml exposes engine and custom config`() {
        val config = ConfigLoader.load("application.yaml")

        assertEquals("8765", config.property("ktor.deployment.port").getString())
        assertEquals(listOf("ca.floo.roadtrip.MainKt.module"), config.property("ktor.application.modules").getList())
        assertEquals("https://api.campflare.com/v2", config.property("roadtrip.campflare.api-base-url").getString())
    }

    @Test
    fun `load defaults to local profile properties`() {
        val props = ApplicationProperties.load(env = emptyMap())

        assertEquals(".", props["roadtrip.static-dir"])
        assertEquals("poi-registry.yaml", props["roadtrip.poi-registry.resource"])
        assertEquals("", props["roadtrip.poi-registry.path"])
        assertEquals("http://127.0.0.1:3000/dash/", props["roadtrip.grafana.root-url"])
        assertEquals("http://127.0.0.1:8765", props["roadtrip.web.root-url"])
        assertEquals("http://127.0.0.1:8770", props["roadtrip.booking.recgov-atc.companion-base-url"])
        assertEquals("180s", props["roadtrip.booking.recgov-atc.companion-timeout"])
        assertEquals("", props["roadtrip.email.resend-api-key"])
        assertEquals("Roadtrip Alerts <roadtrip@floo.ca>", props["roadtrip.email.from"])
        assertEquals("", props["roadtrip.email.default-to"])
        assertEquals(
            "aspira,campflare,recgov,reserveamerica,reservecalifornia",
            props["roadtrip.read-path.enabled-availability-providers"],
        )
    }

    @Test
    fun `load uses selected profile properties`() {
        val props =
            withSystemProperties(
                "POSTGRES_URL" to "jdbc:postgresql://postgres:5432/roadtrip",
                "POSTGRES_USER" to "roadtrip",
                "POSTGRES_PASSWORD" to "roadtrip",
            ) {
                ApplicationProperties.load(env = mapOf("ROADTRIP_PROFILE" to "prod"))
            }

        assertEquals("/app/static", props["roadtrip.static-dir"])
        assertEquals("jdbc:postgresql://postgres:5432/roadtrip", props["roadtrip.db.url"])
        assertEquals("roadtrip", props["roadtrip.db.user"])
        assertEquals("roadtrip", props["roadtrip.db.password"])
        assertEquals("poi-registry.yaml", props["roadtrip.poi-registry.resource"])
        assertEquals("", props["roadtrip.poi-registry.path"])
        assertEquals("https://roadtrip.floo.ca/dash/", props["roadtrip.grafana.root-url"])
        assertEquals("http://recgov-companion:8770", props["roadtrip.booking.recgov-atc.companion-base-url"])
        assertEquals("180s", props["roadtrip.booking.recgov-atc.companion-timeout"])
    }

    @Test
    fun `compose local profile uses container paths with local browser links`() {
        val props =
            withSystemProperties(
                "POSTGRES_URL" to "jdbc:postgresql://postgres:5432/roadtrip",
                "POSTGRES_USER" to "roadtrip",
                "POSTGRES_PASSWORD" to "roadtrip",
            ) {
                ApplicationProperties.load(env = mapOf("ROADTRIP_PROFILE" to "compose-local"))
            }

        assertEquals("/app/static", props["roadtrip.static-dir"])
        assertEquals("jdbc:postgresql://postgres:5432/roadtrip", props["roadtrip.db.url"])
        assertEquals("roadtrip", props["roadtrip.db.user"])
        assertEquals("roadtrip", props["roadtrip.db.password"])
        assertEquals("http://127.0.0.1:3000/dash/", props["roadtrip.grafana.root-url"])
        assertEquals("http://127.0.0.1:8765", props["roadtrip.web.root-url"])
        assertEquals("http://recgov-companion:8770", props["roadtrip.booking.recgov-atc.companion-base-url"])
        assertEquals("180s", props["roadtrip.booking.recgov-atc.companion-timeout"])
    }

    @Test
    fun `environment values do not override profile properties directly`() {
        val props =
            ApplicationProperties.load(
                env =
                    mapOf(
                        "roadtrip.web.root-url" to "https://override.example",
                    ),
            )

        assertEquals("http://127.0.0.1:8765", props["roadtrip.web.root-url"])
        assertEquals(".", props["roadtrip.static-dir"])
    }

    @Test
    fun `prod profile resolves database properties from postgres environment`() {
        val props =
            withSystemProperties(
                "POSTGRES_URL" to "jdbc:postgresql://postgres:5432/roadtrip_prod",
                "POSTGRES_USER" to "app",
                "POSTGRES_PASSWORD" to "secret",
            ) {
                ApplicationProperties.load(env = mapOf("ROADTRIP_PROFILE" to "prod"))
            }

        assertEquals("jdbc:postgresql://postgres:5432/roadtrip_prod", props["roadtrip.db.url"])
        assertEquals("app", props["roadtrip.db.user"])
        assertEquals("secret", props["roadtrip.db.password"])
    }

    @Test
    fun `load overlays selected profile yaml after base yaml`() {
        val props =
            withSystemProperties("SECRET_VALUE" to "from-env") {
                ApplicationProperties.load(
                    env = emptyMap(),
                    classLoader =
                        resourceClassLoader(
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
                        resourceClassLoader(
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
                        resourceClassLoader(
                            "application.yaml" to "roadtrip:\n  static-dir: .",
                        ),
                )
            }

        assertEquals("application config resource 'application-typo.yaml' not found", err.message)
    }

    private fun resourceClassLoader(vararg resources: Pair<String, String>): ClassLoader {
        val byName = resources.toMap()
        return object : ClassLoader(null) {
            override fun getResource(name: String): URL? =
                byName[name]?.let { content ->
                    URL(
                        null,
                        "memory:$name",
                        object : URLStreamHandler() {
                            override fun openConnection(url: URL): URLConnection =
                                object : URLConnection(url) {
                                    override fun connect() = Unit

                                    override fun getInputStream(): InputStream = ByteArrayInputStream(content.toByteArray())
                                }
                        },
                    )
                }

            override fun getResourceAsStream(name: String) = byName[name]?.byteInputStream()
        }
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
