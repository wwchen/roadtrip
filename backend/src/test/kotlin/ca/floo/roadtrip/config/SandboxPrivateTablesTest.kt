package ca.floo.roadtrip.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards that every PII-bearing table is COVERED by the sandbox snapshot
 * privacy mechanism — either excluded at dump time (via the roots list) or
 * scrubbed in place after restore (via sandbox_scrub.sql).
 *
 * Two complementary heuristics derive the candidate set from migration SQL:
 *
 *   1. **FK heuristic** — tables with a column `REFERENCES app_user`, plus
 *      `app_user` itself.  Catches ownership tables (user_identity, user_role,
 *      user_session, user_settings, …).
 *
 *   2. **PII-column heuristic** — any table whose CREATE TABLE block or
 *      ALTER TABLE ADD COLUMN statement contains a column whose name matches
 *      [sensitiveColumnPatterns].  Catches notification/intent tables that
 *      have no app_user FK but carry user notification destinations (e.g.
 *      availability_watch.trigger_config).
 *
 * **Coverage model (new)**:
 *   A derived PII table T is covered when at least one of:
 *   (a) T is in `scripts/sandbox-private-tables.txt` (excluded at dump time).
 *   (b) T == "availability_watch" AND `scripts/sandbox_scrub.sql` contains an
 *       UPDATE that blanks availability_watch.trigger_config.
 *
 *   Any derived PII table that is neither in the roots list nor covered by a
 *   scrub → red build, naming the table and explaining how to fix it.
 *
 * Together these make "someone forgot to exclude/scrub a PII table" a red
 * build rather than a silent snapshot leak.
 */
class SandboxPrivateTablesTest {
    private val repoRoot =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "secrets/registry.yaml").isFile }

    @Test
    fun `every PII-bearing table is excluded or scrubbed in sandbox snapshots`() {
        val rootsList = readPrivateTablesList()
        val derivedTables = derivePiiTables()
        val scrubCoversAvailabilityWatch = scrubFileCoversAvailabilityWatch()

        val uncovered =
            derivedTables
                .filter { table ->
                    when {
                        table in rootsList -> false
                        table == "availability_watch" && scrubCoversAvailabilityWatch -> false
                        else -> true
                    }
                }.toSet()

        assertTrue(
            uncovered.isEmpty(),
            buildString {
                append(
                    "Tables auto-detected as PII-bearing are not covered by the sandbox " +
                        "privacy mechanism: $uncovered.\n" +
                        "Fix by doing one of:\n" +
                        "  (a) Add the table to scripts/sandbox-private-tables.txt " +
                        "(excluded at dump time; FK-dependent closure is auto-computed).\n" +
                        "  (b) Add an UPDATE scrub to scripts/sandbox_scrub.sql and update " +
                        "this test's coverage check to include the table.",
                )
                append(
                    "\n\nFull derived set:  $derivedTables" +
                        "\nRoots list:        $rootsList" +
                        "\nScrub covers availability_watch: $scrubCoversAvailabilityWatch",
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
     * Returns true when `scripts/sandbox_scrub.sql` contains an UPDATE
     * statement that targets availability_watch.trigger_config.
     * This verifies the scrub file actually covers the only PII column in
     * the watch subtree.
     */
    private fun scrubFileCoversAvailabilityWatch(): Boolean {
        val scrubFile = File(repoRoot, "scripts/sandbox_scrub.sql")
        if (!scrubFile.exists()) return false
        val content = scrubFile.readText()
        // Must contain both "availability_watch" and "trigger_config" to count.
        return content.contains("availability_watch", ignoreCase = true) &&
            content.contains("trigger_config", ignoreCase = true)
    }

    /**
     * Derives PII-bearing tables from migration SQL files using two heuristics:
     *
     *   1. FK heuristic: `app_user` itself, plus any table with a column
     *      `REFERENCES app_user` in its CREATE TABLE block.
     *
     *   2. PII-column heuristic: any table whose CREATE TABLE block, or any
     *      ALTER TABLE ... ADD COLUMN statement, contains a column whose
     *      definition matches one of [sensitiveColumnPatterns].
     *
     * Parse strategy:
     *   - For CREATE TABLE: track the current table name while scanning lines
     *     inside the block; apply both heuristics to each column-definition line.
     *   - For ALTER TABLE ADD COLUMN: extract the table name from each ALTER
     *     statement, collect continuation lines until the statement ends (`;`),
     *     then apply both heuristics to each ADD COLUMN fragment found.
     *     Handles multi-ADD forms: `ALTER TABLE t ADD COLUMN a TYPE, ADD COLUMN b TYPE;`
     *     and `ADD COLUMN IF NOT EXISTS col TYPE`.
     *
     * This ensures a future `ALTER TABLE x ADD COLUMN notification_email TEXT`
     * is caught even if the CREATE TABLE block predates the PII naming.
     */
    private fun derivePiiTables(): Set<String> {
        val migrationDir = File(repoRoot, "backend/src/main/resources/db/migration")
        val sqlFiles = migrationDir.listFiles { f -> f.extension == "sql" }.orEmpty()

        val piiTables = mutableSetOf("app_user")
        val createTableRegex = Regex("""(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)""")
        val alterTableRegex = Regex("""(?i)ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(\w+)""")
        // ADD COLUMN fragment: captures the column name (and rest of its definition)
        // after ADD COLUMN [IF NOT EXISTS].
        val addColumnRegex = Regex("""(?i)ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s+(.+)""")
        // Extracts the leading column name from a CREATE TABLE column-definition line.
        val columnNameRegex = Regex("""^\s+(\w+)\s""")

        for (file in sqlFiles) {
            val lines = file.readLines()
            var currentCreateTable: String? = null
            // ALTER TABLE accumulator state
            var currentAlterTable: String? = null
            val alterBuffer = StringBuilder()

            for (line in lines) {
                // ── Detect ALTER TABLE start ───────────────────────────────
                val alterMatch = alterTableRegex.find(line)
                if (alterMatch != null && line.trimStart().uppercase().startsWith("ALTER")) {
                    // Flush any prior alter buffer (shouldn't happen with well-formed SQL,
                    // but be safe).
                    val prevAlter = currentAlterTable
                    if (prevAlter != null && alterBuffer.isNotEmpty()) {
                        processAlterBuffer(prevAlter, alterBuffer.toString(), piiTables, addColumnRegex)
                    }
                    val newAlterTable = alterMatch.groupValues[1].lowercase()
                    currentAlterTable = newAlterTable
                    alterBuffer.clear()
                    alterBuffer.append(line)
                    if (line.contains(";")) {
                        processAlterBuffer(newAlterTable, alterBuffer.toString(), piiTables, addColumnRegex)
                        currentAlterTable = null
                        alterBuffer.clear()
                    }
                    // ALTER TABLE resets CREATE TABLE context (can't be inside CREATE).
                    currentCreateTable = null
                    continue
                }

                // ── Continue accumulating ALTER TABLE statement ────────────
                val activeAlter = currentAlterTable
                if (activeAlter != null) {
                    alterBuffer.append(" ").append(line)
                    if (line.contains(";")) {
                        processAlterBuffer(activeAlter, alterBuffer.toString(), piiTables, addColumnRegex)
                        currentAlterTable = null
                        alterBuffer.clear()
                    }
                    continue
                }

                // ── Detect CREATE TABLE ────────────────────────────────────
                val tableMatch = createTableRegex.find(line)
                if (tableMatch != null) {
                    currentCreateTable = tableMatch.groupValues[1].lowercase()
                }
                if (currentCreateTable == null) continue

                // ── Apply heuristics inside CREATE TABLE block ─────────────
                val activeTable = currentCreateTable

                // Heuristic 1: FK to app_user
                if (line.contains("REFERENCES app_user", ignoreCase = true)) {
                    piiTables.add(activeTable)
                }

                // Heuristic 2: PII column name — skip lines with no leading column token
                columnNameRegex.find(line) ?: continue
                val colDef = line.trim()
                if (sensitiveColumnPatterns.any { it.containsMatchIn(colDef) }) {
                    piiTables.add(activeTable)
                }
            }
        }

        return piiTables
    }

    /**
     * Processes a collected ALTER TABLE statement buffer, applying the PII-column
     * heuristic to every ADD COLUMN fragment found within it.
     */
    private fun processAlterBuffer(
        tableName: String,
        buffer: String,
        piiTables: MutableSet<String>,
        addColumnRegex: Regex,
    ) {
        // Split on commas to handle multi-ADD forms:
        //   ALTER TABLE t ADD COLUMN a TYPE, ADD COLUMN b TYPE;
        // Each segment is checked for an ADD COLUMN clause.
        val segments = buffer.split(",")
        for (segment in segments) {
            val addMatch = addColumnRegex.find(segment) ?: continue
            val colName = addMatch.groupValues[1]
            val colDef = "$colName ${addMatch.groupValues[2]}".trim()
            if (sensitiveColumnPatterns.any { it.containsMatchIn(colDef) }) {
                piiTables.add(tableName)
            }
        }
    }

    companion object {
        /**
         * Column names that indicate a table holds PII or notification
         * destinations.  Match against the column-definition token that
         * immediately follows a whitespace/comma boundary, so we avoid false
         * positives from booleans like `notify_slack` or `email_verified`.
         *
         * Patterns are matched case-insensitively against the trimmed column
         * name extracted from CREATE TABLE lines or ALTER TABLE ADD COLUMN
         * fragments.
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
