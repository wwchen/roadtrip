package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.ApiContract
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = "usage: ContractLedgerCheck <path/to/exercised.tsv>"
private const val DRIFT_EXIT_CODE = 1
private const val KIND_FIELD = 0
private const val METHOD_FIELD = 1
private const val PATH_FIELD = 2
private const val STATUS_FIELD = 3

/**
 * Fields a line must have before it is read. The writer always emits five, so a
 * shorter one is a half-written file — a JVM killed mid-append — and dropping it
 * is what keeps this an exit code with a message rather than an
 * `IndexOutOfBoundsException` for whoever is reading the output under pressure.
 */
private const val LEDGER_FIELD_COUNT = 5

/** The header line is two fields: the kind, and the digest it carries. */
private const val DIGEST_FIELD_COUNT = 2
private const val DIGEST_FIELD = 1

/**
 * Every contract row with a success body was produced by some route test.
 *
 * `ContractBodyCheck` holds each body a test produces to its row; this holds the
 * *suite* to the contract, so a row nothing exercises cannot quietly stop being
 * covered. Any `VIOLATION` line fails too: a throw inside the send pipeline can
 * in principle be swallowed into a 500, and the ledger is then the record that
 * fails the build.
 *
 * Only [LEDGER_EXERCISED] counts. A fixture app's pass is written under
 * [LEDGER_EXPECTED_EXERCISED] and ignored here: `ContractBodyCheckTest` mounts a
 * stub on a live row's path, and a row covered only by that is a row no real
 * route test produces.
 *
 * The ledger's first line carries `contractDigest()`. A mismatch means the file
 * was written against a different `ApiContract` than the one on this task's
 * classpath — `:backend:contractLedgerCheck` invoked without `:backend:test`,
 * with a stale file on disk — and is refused rather than reported green.
 *
 * A `JavaExec` rather than a second `Test` task deliberately — a second `Test`
 * task joins Kover's instrumented set and changes what `koverVerify` measures —
 * and an argv-and-exit verifier in the same shape as `apigen/GenerateApiTypes`.
 */
fun main(args: Array<String>) {
    val ledger = File(args.firstOrNull() ?: error(USAGE))
    if (!ledger.exists()) {
        System.err.println(
            "${ledger.path} is missing. :backend:contractLedgerCheck must run after :backend:test, " +
                "which is what writes it.",
        )
        exitProcess(DRIFT_EXIT_CODE)
    }
    val rows = ledger.readLines().filter { it.isNotBlank() }.map { it.split(LEDGER_FIELD_SEPARATOR) }
    staleLedger(rows)?.let {
        System.err.println(it)
        exitProcess(DRIFT_EXIT_CODE)
    }
    val lines = rows.filter { it.size >= LEDGER_FIELD_COUNT }
    val violations = lines.filter { it[KIND_FIELD] == LEDGER_VIOLATION }
    val produced =
        lines
            .filter { it[KIND_FIELD] == LEDGER_EXERCISED }
            .mapTo(HashSet()) { Triple(it[METHOD_FIELD], it[PATH_FIELD], it[STATUS_FIELD]) }
    val unexercised =
        ApiContract.endpoints
            .filter { it.success.body != null }
            .filterNot {
                Triple(it.method.wireValue, it.path, it.success.status.toString()) in produced
            }.map { "${it.method.wireValue} ${it.path} (${it.success.status})" }
            .sorted()
    val unverified = unverifiedEntries(produced)

    if (violations.isEmpty() && unexercised.isEmpty()) {
        println(
            "${produced.size} contracted responses exercised; every row with a success body is covered; " +
                "${unverified.size} declared entries unverified",
        )
        reportUnverified(unverified)
        return
    }
    violations.forEach { System.err.println("contract body drift: ${it.joinToString(" ")}") }
    if (unexercised.isNotEmpty()) {
        System.err.println(
            "no route test produced the success body of:\n  ${unexercised.joinToString("\n  ")}\n" +
                "Add a 2xx route test on routeTestApplication for each, or drop the row.",
        )
    }
    exitProcess(DRIFT_EXIT_CODE)
}

/** Why this ledger cannot be trusted, or null when it can. */
private fun staleLedger(rows: List<List<String>>): String? {
    val header = rows.firstOrNull()?.takeIf { it.size == DIGEST_FIELD_COUNT && it[KIND_FIELD] == LEDGER_DIGEST_KIND }
    val digest = header?.get(DIGEST_FIELD)
    val expected = contractDigest()
    if (digest == expected) return null
    return "the contract ledger does not match ApiContract on this task's classpath " +
        "(ledger ${digest ?: "has no digest line"}, contract $expected). It was written by an earlier " +
        "run against a different contract. Rerun :backend:test."
}

/**
 * Every `(method, path, status, class)` the contract declares a body for that no
 * test produced.
 *
 * Informational, not a failure. The check is one-directional by construction —
 * it fails a produced response the row does not declare, and nothing can fail a
 * declared response no route serves — so this is the list that says how much of
 * the published document is a claim rather than a verified fact. It shrinks when
 * a test produces the entry or when the row stops declaring it.
 */
private fun unverifiedEntries(produced: Set<Triple<String, String, String>>): List<String> =
    ApiContract.endpoints
        .flatMap { row -> (listOf(row.success) + row.errors).map { row to it } }
        .filter { (_, body) -> body.body != null }
        .filterNot { (row, body) -> Triple(row.method.wireValue, row.path, body.status.toString()) in produced }
        .map { (row, body) -> "${row.method.wireValue} ${row.path} ${body.status} ${body.body?.simpleName}" }
        .sorted()

private fun reportUnverified(unverified: List<String>) {
    if (unverified.isEmpty()) return
    println("declared but never produced by a test:\n  ${unverified.joinToString("\n  ")}")
}
