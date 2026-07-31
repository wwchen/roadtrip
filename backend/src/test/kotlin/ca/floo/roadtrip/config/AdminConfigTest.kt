package ca.floo.roadtrip.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AdminConfigTest {
    private fun section(value: String?) =
        ConfigSection(
            value?.let { mapOf("roadtrip.admin.bootstrap-emails" to it) } ?: emptyMap(),
        ).section("roadtrip.admin")

    @Test
    fun `a comma-separated list parses`() {
        val config = AdminConfig.fromConfig(section("ops@example.com,dev@example.com"))

        assertEquals(setOf("ops@example.com", "dev@example.com"), config.bootstrapEmails)
    }

    @Test
    fun `addresses are lowercased so the comparison site does not have to be`() {
        // app_user.email is stored lowercase; normalizing here means the grant
        // check is a plain set membership test.
        val config = AdminConfig.fromConfig(section("Ops@Example.COM"))

        assertEquals(setOf("ops@example.com"), config.bootstrapEmails)
    }

    @Test
    fun `surrounding whitespace and empty entries are discarded`() {
        val config = AdminConfig.fromConfig(section(" ops@example.com , , dev@example.com ,"))

        assertEquals(setOf("ops@example.com", "dev@example.com"), config.bootstrapEmails)
    }

    @Test
    fun `unset or blank means nobody, not an error`() {
        // The state every environment starts in, and the one CI runs in.
        assertEquals(emptySet(), AdminConfig.fromConfig(section(null)).bootstrapEmails)
        assertEquals(emptySet(), AdminConfig.fromConfig(section("")).bootstrapEmails)
        assertEquals(emptySet(), AdminConfig.fromConfig(section("   ")).bootstrapEmails)
        assertEquals(emptySet(), AdminConfig.fromConfig(section(",,")).bootstrapEmails)
    }

    @Test
    fun `a single address is a list of one`() {
        assertEquals(setOf("solo@example.com"), AdminConfig.fromConfig(section("solo@example.com")).bootstrapEmails)
    }
}
