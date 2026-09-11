package ca.floo.roadtrip.fixtures

import java.io.File

private const val REPO_ROOT_MARKER = "secrets/registry.yaml"
private const val SQL_FENCE_OPEN = "```sql"
private const val FENCE = "```"
private val markdownHeading = Regex("^#{1,6} ")

/** The booking-port deploy runbook, and the section headings its guard tests execute. */
internal val bookingPortRunbook: File by lazy { repoFile("docs/reservation-providers.md") }
internal const val ROLLBACK_HEADING = "### Rolling back past V59"
internal const val ROLL_FORWARD_HEADING = "### Rolling forward again"
internal const val ROLL_FORWARD_CREDENTIALS_HEADING = "### Rolling forward rec.gov credentials"

/** The repo root, found by walking up from the test's working directory. */
internal val repoRoot: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, REPO_ROOT_MARKER).isFile }

internal fun repoFile(path: String): File = File(repoRoot, path).also { check(it.isFile) { "missing repo file $path" } }

/**
 * The statements a runbook section tells an operator to run: every fenced `sql`
 * block under [heading] in [doc], split on its terminators. The doc is the
 * source of truth, so a guard test runs exactly what a person would paste —
 * and fails loudly when the heading or its SQL goes missing.
 */
internal fun runbookSqlStatements(
    doc: File,
    heading: String,
): List<String> {
    val lines = doc.readLines()
    val start = lines.indexOfFirst { it.trim() == heading }
    check(start >= 0) { "${doc.name} has no \"$heading\" heading" }

    val blocks = mutableListOf<String>()
    var block: StringBuilder? = null
    for (line in lines.drop(start + 1).takeWhile { !markdownHeading.containsMatchIn(it) }) {
        val open = block
        when {
            open == null -> if (line.trim() == SQL_FENCE_OPEN) block = StringBuilder()
            line.trim() == FENCE -> {
                blocks += open.toString()
                block = null
            }
            else -> open.appendLine(line)
        }
    }
    check(blocks.isNotEmpty()) { "${doc.name}'s \"$heading\" section has no fenced sql block" }

    return blocks
        .flatMap { it.split(";") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
