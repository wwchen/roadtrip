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

/**
 * Every contract row with a success body was produced by some route test.
 *
 * `ContractBodyCheck` holds each body a test produces to its row; this holds the
 * *suite* to the contract, so a row nothing exercises cannot quietly stop being
 * covered. Any `VIOLATION` line fails too: a throw inside the send pipeline can
 * in principle be swallowed into a 500, and the ledger is then the record that
 * fails the build.
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
    val lines =
        ledger
            .readLines()
            .filter { it.isNotBlank() }
            .map { it.split(LEDGER_FIELD_SEPARATOR) }
            .filter { it.size >= LEDGER_FIELD_COUNT }
    val violations = lines.filter { it[KIND_FIELD] == LEDGER_VIOLATION }
    val exercised =
        lines
            .filter { it[KIND_FIELD] == LEDGER_EXERCISED }
            .mapTo(HashSet()) { Triple(it[METHOD_FIELD], it[PATH_FIELD], it[STATUS_FIELD]) }
    val unexercised =
        ApiContract.endpoints
            .filter { it.success.body != null }
            .filterNot {
                Triple(it.method.wireValue, it.path, it.success.status.toString()) in exercised
            }.map { "${it.method.wireValue} ${it.path} (${it.success.status})" }
            .sorted()

    if (violations.isEmpty() && unexercised.isEmpty()) {
        println("${exercised.size} contracted responses exercised; every row with a success body is covered")
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
