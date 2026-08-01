package ca.floo.roadtrip.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards that every user-scoped table (tables with a column referencing
 * `app_user(id)`, plus `app_user` itself) is listed in
 * `scripts/sandbox-private-tables.txt`.
 *
 * A new migration that adds a user-scoped table without updating the private
 * table list will fail the build here, turning a silent PII snapshot leak into
 * a red build.
 */
class SandboxPrivateTablesTest {
    private val repoRoot =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "secrets/registry.yaml").isFile }

    @Test
    fun `every user-scoped table is listed in sandbox-private-tables txt`() {
        val listedTables = readPrivateTablesList()
        val derivedTables = deriveUserScopedTables()

        assertEquals(
            derivedTables,
            listedTables,
            buildString {
                val missing = derivedTables - listedTables
                val extra = listedTables - derivedTables
                if (missing.isNotEmpty()) {
                    append(
                        "Tables derived from migrations as user-scoped but MISSING from " +
                            "scripts/sandbox-private-tables.txt: $missing. " +
                            "Add them to prevent PII leaking into sandbox snapshots.",
                    )
                }
                if (extra.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(
                        "Tables listed in scripts/sandbox-private-tables.txt but NOT found " +
                            "as user-scoped in migrations (stale entry?): $extra.",
                    )
                }
            },
        )
    }

    /**
     * Reads `scripts/sandbox-private-tables.txt`, ignoring blank lines and
     * lines starting with `#`.
     */
    private fun readPrivateTablesList(): Set<String> {
        val file = File(repoRoot, "scripts/sandbox-private-tables.txt")
        return file
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    /**
     * Derives the set of user-scoped tables from migration SQL files:
     *  - `app_user` itself (always included)
     *  - any table that has a column with `REFERENCES app_user` in its
     *    `CREATE TABLE` block
     *
     * Parse strategy: scan lines; track the current `CREATE TABLE <name>`;
     * if a line within contains `REFERENCES app_user`, mark that table.
     */
    private fun deriveUserScopedTables(): Set<String> {
        val migrationDir = File(repoRoot, "backend/src/main/resources/db/migration")
        val sqlFiles = migrationDir.listFiles { f -> f.extension == "sql" }.orEmpty()

        val userScopedTables = mutableSetOf("app_user")
        val createTableRegex = Regex("""(?i)CREATE\s+TABLE\s+(\w+)""")

        for (file in sqlFiles) {
            var currentTable: String? = null
            for (line in file.readLines()) {
                val tableMatch = createTableRegex.find(line)
                if (tableMatch != null) {
                    currentTable = tableMatch.groupValues[1].lowercase()
                }
                if (currentTable != null && line.contains("REFERENCES app_user", ignoreCase = true)) {
                    userScopedTables.add(currentTable)
                }
            }
        }

        return userScopedTables
    }
}
