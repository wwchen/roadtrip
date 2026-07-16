package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationPropertiesTest {
    @Test
    fun `load defaults to local profile properties`() {
        val props = ApplicationProperties.load(env = emptyMap())

        assertEquals("8765", props["server.port"])
        assertEquals(".", props["roadtrip.static-dir"])
        assertEquals("poi-registry.yaml", props["roadtrip.poi-registry.resource"])
        assertEquals("", props["roadtrip.poi-registry.path"])
        assertEquals("http://127.0.0.1:3000/dash/", props["roadtrip.grafana.root-url"])
        assertEquals("http://127.0.0.1:8765", props["roadtrip.web.root-url"])
        assertEquals("http://127.0.0.1:8770", props["roadtrip.booking.recgov-atc.companion-base-url"])
        assertEquals("180s", props["roadtrip.booking.recgov-atc.companion-timeout"])
        assertEquals(
            "aspira,campflare,recgov,reserveamerica,reservecalifornia",
            props["roadtrip.read-path.enabled-availability-providers"],
        )
    }

    @Test
    fun `load uses selected profile properties`() {
        val props =
            ApplicationProperties.load(
                env =
                    mapOf(
                        "ROADTRIP_PROFILE" to "prod",
                        "POSTGRES_DB" to "roadtrip",
                        "POSTGRES_USER" to "roadtrip",
                        "POSTGRES_PASSWORD" to "roadtrip",
                    ),
            )

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
            ApplicationProperties.load(
                env =
                    mapOf(
                        "ROADTRIP_PROFILE" to "compose-local",
                        "POSTGRES_DB" to "roadtrip",
                        "POSTGRES_USER" to "roadtrip",
                        "POSTGRES_PASSWORD" to "roadtrip",
                    ),
            )

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
                        "server.port" to "9999",
                        "roadtrip.web.root-url" to "https://override.example",
                    ),
            )

        assertEquals("8765", props["server.port"])
        assertEquals("http://127.0.0.1:8765", props["roadtrip.web.root-url"])
        assertEquals(".", props["roadtrip.static-dir"])
    }

    @Test
    fun `prod profile resolves database properties from postgres environment`() {
        val props =
            ApplicationProperties.load(
                env =
                    mapOf(
                        "ROADTRIP_PROFILE" to "prod",
                        "POSTGRES_DB" to "roadtrip_prod",
                        "POSTGRES_USER" to "app",
                        "POSTGRES_PASSWORD" to "secret",
                    ),
            )

        assertEquals("jdbc:postgresql://postgres:5432/roadtrip_prod", props["roadtrip.db.url"])
        assertEquals("app", props["roadtrip.db.user"])
        assertEquals("secret", props["roadtrip.db.password"])
    }

    @Test
    fun `load resolves property placeholders from environment and other properties`() {
        val props =
            ApplicationProperties.load(
                env = mapOf("SECRET_VALUE" to "from-env"),
                classLoader =
                    resourceClassLoader(
                        "application.yml" to
                            """
                            direct: ${'$'}{SECRET_VALUE}
                            missing: ${'$'}{DOES_NOT_EXIST}
                            chained: ${'$'}{direct}
                            self: ${'$'}{self}
                            list:
                              - one
                              - two
                            """.trimIndent(),
                        "application-local.yml" to "",
                    ),
            )

        assertEquals("from-env", props["direct"])
        assertEquals("", props["missing"])
        assertEquals("from-env", props["chained"])
        assertEquals("", props["self"])
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
                            "application.yml" to "server:\n  admin-port: 8766",
                        ),
                )
            }

        assertEquals("application config resource 'application-typo.yml' not found", err.message)
    }

    private fun resourceClassLoader(vararg resources: Pair<String, String>): ClassLoader {
        val byName = resources.toMap()
        return object : ClassLoader(null) {
            override fun getResourceAsStream(name: String) = byName[name]?.byteInputStream()
        }
    }
}
