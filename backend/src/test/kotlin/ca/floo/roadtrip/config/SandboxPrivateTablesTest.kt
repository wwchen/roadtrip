package ca.floo.roadtrip.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards that every PII-bearing table is listed in
 * `scripts/sandbox-private-tables.txt`.
 *
 * Two complementary heuristics derive the candidate set from migration SQL:
 *
 *   1. **FK heuristic** — tables with a column `REFERENCES app_user`, plus
 *      `app_user` itself.  Catches ownership tables (user_identity, user_role,
 *      user_session, user_settings, …).
 *
 *   2. **PII-column heuristic** — any table whose CREATE TABLE block contains
 *      a column whose name matches [sensitiveColumnPatterns].  Catches
 *      notification/intent tables that have no app_user FK but carry user
 *      notification destinations (e.g. availability_watch.trigger_config).
 *
 * Together they make "someone forgot to exclude a PII table" a red build
 * rather than a silent snapshot leak.
 *
 * Assertion: every derived table MUST be in the listed set (derived ⊆ listed).
 * Extra listed tables are allowed — over-exclusion (e.g. cascade children
 * like availability_watch_target) is safe; under-exclusion leaks PII.
 */
class SandboxPrivateTablesTest {
    private val repoRoot =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "secrets/registry.yaml").isFile }

    @Test
    fun `every PII-bearing table is listed in sandbox-private-tables txt`() {
        val listedTables = readPrivateTablesList()
        val derivedTables = derivePiiTables()

        val missing = derivedTables - listedTables
        assertTrue(
            missing.isEmpty(),
            buildString {
                append(
                    "Tables auto-detected as PII-bearing but MISSING from " +
                        "scripts/sandbox-private-tables.txt: $missing. " +
                        "Add them (and their FK-dependent cascade children) to prevent " +
                        "PII leaking into sandbox snapshots.",
                )
                append(
                    "\n\nFull derived set: $derivedTables" +
                        "\nFull listed set:  $listedTables",
                )
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
     * Derives PII-bearing tables from migration SQL files using two heuristics:
     *
     *   1. FK heuristic: `app_user` itself, plus any table with a column
     *      `REFERENCES app_user` in its CREATE TABLE block.
     *
     *   2. PII-column heuristic: any table whose CREATE TABLE block contains a
     *      column whose definition line matches one of [sensitiveColumnPatterns].
     *
     * Parse strategy: scan lines; track the current `CREATE TABLE <name>`; apply
     * both heuristics to lines within that block.  A line matching a new
     * CREATE TABLE resets the current-table tracker.
     */
    private fun derivePiiTables(): Set<String> {
        val migrationDir = File(repoRoot, "backend/src/main/resources/db/migration")
        val sqlFiles = migrationDir.listFiles { f -> f.extension == "sql" }.orEmpty()

        val piiTables = mutableSetOf("app_user")
        val createTableRegex = Regex("""(?i)CREATE\s+TABLE\s+(\w+)""")
        // Extracts the leading column name from a table-column definition line.
        // A column line looks like:  "  col_name  TYPE  [constraints]"
        val columnNameRegex = Regex("""^\s+(\w+)\s""")

        for (file in sqlFiles) {
            var currentTable: String? = null
            for (line in file.readLines()) {
                val tableMatch = createTableRegex.find(line)
                if (tableMatch != null) {
                    currentTable = tableMatch.groupValues[1].lowercase()
                }
                if (currentTable == null) continue

                // Heuristic 1: FK to app_user
                if (line.contains("REFERENCES app_user", ignoreCase = true)) {
                    piiTables.add(currentTable)
                }

                // Heuristic 2: PII column name — skip lines with no leading column token
                columnNameRegex.find(line) ?: continue
                val colDef = line.trim()
                if (sensitiveColumnPatterns.any { it.containsMatchIn(colDef) }) {
                    piiTables.add(currentTable)
                }
            }
        }

        return piiTables
    }

    companion object {
        /**
         * Column names that indicate a table holds PII or notification
         * destinations.  Match against the column-definition token that
         * immediately follows a whitespace/comma boundary, so we avoid false
         * positives from booleans like `notify_slack` or `email_verified`.
         *
         * Patterns are matched case-insensitively against the trimmed column
         * name extracted from CREATE TABLE lines.
         */
        val sensitiveColumnPatterns: List<Regex> =
            listOf(
                Regex("""(?i)\btrigger_config\b"""),
                Regex("""(?i)\bnotification_email\b"""),
                Regex("""(?i)\bslack_channel\b"""),
                Regex("""(?i)\bslack_token\b"""), // covers slack_token_cipher, slack_token_hint
                Regex("""(?i)\brecipient\b"""), // covers recipient, recipients, recipient_email
                Regex("""(?i)^\s*email\s"""), // bare "email" column, not email_verified etc.
            )
    }
}
