package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.etl.EtlOrchestrator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

class TargetNotFoundException(
    name: String,
) : RuntimeException("unknown target: $name")

class TargetBusyException(
    val target: String,
    val runningRunId: Long,
) : RuntimeException("target=$target is already running as run_id=$runningRunId")

// What a run does. Each target has both a fetch list (web → data/) and an
// import list (data/ → Postgres). They share a per-target mutex so a fetch
// and an import on the same target serialize.
enum class RunKind(
    val rowValue: String,
) {
    FETCH("fetch"),
    IMPORT("import"),
}

data class RunOutcome(
    val parentRunId: Long,
    val target: String,
    val kind: RunKind,
    val status: String, // 'completed' | 'failed' | 'noop'
    val failedPhase: String?,
)

// Per-target locked, structured-record orchestrator. RFC 0004 / issue #44.
//
// Sequence per startRun(target, kind):
//   1. tryLock the target's mutex; on contention, throw TargetBusyException
//      carrying the existing parent run_id so the caller can return 409.
//   2. Insert a parent ingest_runs row (phase_kind='target',
//      phase='fetch'|'import', status='started').
//   3. For each phase in the chosen kind's list, insert a phase row, run
//      it, finalize the row.
//   4. On any phase failure, mark parent failed and skip remaining phases.
//   5. On success, mark parent completed.
//   6. Always release the mutex in finally.
//
// startRun is suspending and returns when the entire run finishes (sync POST
// is the default). Callers that want fire-and-forget wrap in scope.async.
class IngestController(
    private val ctx: DSLContext,
    private val etl: EtlOrchestrator,
    private val fetchTargets: Map<String, Target>,
    private val importTargets: Map<String, Target>,
    private val workingDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val processFactory: ProcessFactory = DefaultProcessFactory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Per-target mutex. Fetch and import keyspaces are disjoint (slug vs.
    // poi_data name); use a combined map keyed by "<kind>:<name>" so
    // a fetch and an import for the same upstream don't share a lock —
    // they read/write different files.
    private val locks: Map<String, Mutex> =
        (fetchTargets.keys.map { "fetch:$it" } + importTargets.keys.map { "import:$it" })
            .associateWith { Mutex() }

    private val active: MutableMap<String, Long> = mutableMapOf()

    /** All targets across both fetch and import maps (de-duplicated). */
    fun knownTargets(): Set<String> = fetchTargets.keys + importTargets.keys

    /** Fan-out targets for [kind]. */
    fun fanOutTargets(kind: RunKind): Set<String> =
        when (kind) {
            RunKind.FETCH -> fetchTargets.keys
            RunKind.IMPORT -> importTargets.keys
        }

    suspend fun startRun(
        targetName: String,
        kind: RunKind,
        triggeredBy: String,
    ): RunOutcome {
        val targets = if (kind == RunKind.FETCH) fetchTargets else importTargets
        val target = targets[targetName] ?: throw TargetNotFoundException(targetName)
        val lockKey = "${kind.rowValue}:$targetName"
        val mutex = locks[lockKey]!!

        if (!mutex.tryLock()) {
            val existing =
                synchronized(active) { active[lockKey] }
                    ?: error("mutex held but no active run_id for $lockKey")
            throw TargetBusyException(targetName, existing)
        }

        val phases: List<Phase> =
            when (kind) {
                RunKind.FETCH -> target.fetchPhases
                RunKind.IMPORT -> target.importPhases
            }

        val parentId = createParentRow(target.name, kind, triggeredBy)
        synchronized(active) { active[lockKey] = parentId }
        log.info(
            "ingest_runs id={} target={} kind={} started ({} phases)",
            parentId,
            target.name,
            kind.rowValue,
            phases.size,
        )

        try {
            return runPhases(target, kind, phases, parentId)
        } finally {
            synchronized(active) { active.remove(lockKey) }
            mutex.unlock()
        }
    }

    private suspend fun runPhases(
        target: Target,
        kind: RunKind,
        phases: List<Phase>,
        parentId: Long,
    ): RunOutcome {
        // Empty phase list is a legitimate no-op (e.g. parks-canada-curated
        // has no fetch step). Mark the parent completed and return; this
        // shows up cleanly on the dashboard rather than as a phantom row.
        if (phases.isEmpty()) {
            completeParent(parentId)
            return RunOutcome(parentId, target.name, kind, "noop", null)
        }

        for (phase in phases) {
            val phaseId = createPhaseRow(parentId, target.name, phase)
            try {
                val counts =
                    when (phase) {
                        is Phase.Fetch -> runFetch(phase)
                        is Phase.Import -> runImport(phase)
                    }
                completePhase(phaseId, counts)
                log.info("ingest_runs id={} phase={} completed", phaseId, phase.label)
            } catch (e: Throwable) {
                val (notes, exit) = phaseFailureNotes(e)
                failPhase(phaseId, notes, exit)
                failParent(parentId, "phase=${phase.label}: ${notes.take(300)}")
                log.warn("ingest_runs id={} phase={} failed: {}", phaseId, phase.label, notes.take(300))
                return RunOutcome(parentId, target.name, kind, "failed", phase.label)
            }
        }
        completeParent(parentId)
        return RunOutcome(parentId, target.name, kind, "completed", null)
    }

    // -- Fetch phases (web → data/) -------------------------------------------
    private suspend fun runFetch(phase: Phase.Fetch): JSONB =
        withContext(ioDispatcher) {
            val process =
                processFactory.start(
                    cmd = phase.cmd,
                    workingDir = workingDir,
                )

            // Drain stdout to logger (line-stream so a hung script with output
            // is visible) and stderr to a 4KB ring buffer for the row's notes.
            val stdoutDrain =
                CoroutineScope(ioDispatcher).async {
                    BufferedReader(InputStreamReader(process.stdoutStream())).useLines { lines ->
                        lines.forEach { log.info("[{}] {}", phase.label, it) }
                    }
                }
            val stderrTail = StringBuilder()
            val stderrDrain =
                CoroutineScope(ioDispatcher).async {
                    BufferedReader(InputStreamReader(process.stderrStream())).useLines { lines ->
                        lines.forEach { line ->
                            log.info("[{}] {}", phase.label, line)
                            synchronized(stderrTail) {
                                stderrTail.appendLine(line)
                                if (stderrTail.length > STDERR_TAIL_BYTES) {
                                    stderrTail.delete(0, stderrTail.length - STDERR_TAIL_BYTES)
                                }
                            }
                        }
                    }
                }

            val finished =
                withTimeoutOrNull(Duration.ofSeconds(phase.timeoutSec).toMillis()) {
                    process.awaitExit()
                }

            if (finished == null) {
                // Best-effort kill of the process tree (Process.descendants on
                // JDK 9+) so child curls/python don't outlive the timeout.
                process.killTree()
                stdoutDrain.cancel()
                stderrDrain.cancel()
                throw FetchTimeoutException("phase ${phase.label} exceeded ${phase.timeoutSec}s timeout")
            }
            // Wait for drainers to finish so notes carry the full tail.
            runCatching { stdoutDrain.await() }
            runCatching { stderrDrain.await() }

            if (finished != 0) {
                throw FetchFailedException(
                    exitCode = finished,
                    stderrTail = synchronized(stderrTail) { stderrTail.toString() },
                )
            }
            JSONB.valueOf("""{"exit_code":0}""")
        }

    // -- Import phases (data/raw/ + data/etl-out/ → Postgres) -----------------
    //
    // The phase carries a poi_data row's display name. The orchestrator
    // walks that row's full etls: chain — materializing intermediates to
    // data/etl-out/<slug>/ and upserting the terminal etl's Poi.* output.
    private suspend fun runImport(phase: Phase.Import): JSONB =
        withContext(ioDispatcher) {
            val stats = etl.runPoiData(phase.poiDataName)
            JSONB.valueOf(
                """{"import_run_id":${stats.upsertResult.runId},"seen":${stats.upsertResult.seenCount},"swept":${stats.upsertResult.sweptCount},"terminal_etl":"${stats.terminalEtlSlug}"}""",
            )
        }

    // -- ingest_runs row CRUD -------------------------------------------------
    private fun createParentRow(
        target: String,
        kind: RunKind,
        triggeredBy: String,
    ): Long =
        ctx
            .insertInto(INGEST_RUNS)
            .set(INGEST_RUNS.TARGET, target)
            .set(INGEST_RUNS.PHASE, kind.rowValue) // 'fetch' or 'import'
            .set(INGEST_RUNS.PHASE_KIND, "target")
            .set(INGEST_RUNS.STATUS, "started")
            .set(INGEST_RUNS.TRIGGERED_BY, triggeredBy)
            .set(INGEST_RUNS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .returningResult(INGEST_RUNS.ID)
            .fetchOne()!!
            .value1()!!

    private fun createPhaseRow(
        parentId: Long,
        target: String,
        phase: Phase,
    ): Long {
        val kind =
            when (phase) {
                is Phase.Fetch -> "fetch"
                is Phase.Import -> "import"
            }
        return ctx
            .insertInto(INGEST_RUNS)
            .set(INGEST_RUNS.TARGET, target)
            .set(INGEST_RUNS.PHASE, phase.label)
            .set(INGEST_RUNS.PHASE_KIND, kind)
            .set(INGEST_RUNS.PARENT_RUN_ID, parentId)
            .set(INGEST_RUNS.STATUS, "started")
            .set(INGEST_RUNS.TRIGGERED_BY, "phase")
            .set(INGEST_RUNS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .returningResult(INGEST_RUNS.ID)
            .fetchOne()!!
            .value1()!!
    }

    private fun completePhase(
        phaseId: Long,
        counts: JSONB,
    ) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "completed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(INGEST_RUNS.COUNTS, counts)
            .where(INGEST_RUNS.ID.eq(phaseId))
            .execute()
    }

    private fun failPhase(
        phaseId: Long,
        notes: String,
        exitCode: Int?,
    ) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "failed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(INGEST_RUNS.NOTES, notes)
            .apply { if (exitCode != null) set(INGEST_RUNS.EXIT_CODE, exitCode) }
            .where(INGEST_RUNS.ID.eq(phaseId))
            .execute()
    }

    private fun completeParent(parentId: Long) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "completed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(INGEST_RUNS.ID.eq(parentId))
            .execute()
    }

    private fun failParent(
        parentId: Long,
        notes: String,
    ) {
        ctx
            .update(INGEST_RUNS)
            .set(INGEST_RUNS.STATUS, "failed")
            .set(INGEST_RUNS.COMPLETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .set(INGEST_RUNS.NOTES, notes)
            .where(INGEST_RUNS.ID.eq(parentId))
            .execute()
    }

    private fun phaseFailureNotes(e: Throwable): Pair<String, Int?> =
        when (e) {
            is FetchFailedException ->
                "exit=${e.exitCode}\n${e.stderrTail.trim()}" to e.exitCode
            is FetchTimeoutException -> (e.message ?: "timeout") to null
            else -> "${e.javaClass.simpleName}: ${e.message ?: ""}" to null
        }

    companion object {
        const val STDERR_TAIL_BYTES = 4 * 1024
    }
}

class FetchFailedException(
    val exitCode: Int,
    val stderrTail: String,
) : RuntimeException("fetch phase exited $exitCode")

class FetchTimeoutException(
    message: String,
) : RuntimeException(message)

// Indirection to make process spawning testable without forking real procs.
interface ProcessFactory {
    fun start(
        cmd: List<String>,
        workingDir: File,
    ): RunningProcess
}

interface RunningProcess {
    fun stdoutStream(): java.io.InputStream

    fun stderrStream(): java.io.InputStream

    suspend fun awaitExit(): Int

    fun killTree()
}

object DefaultProcessFactory : ProcessFactory {
    override fun start(
        cmd: List<String>,
        workingDir: File,
    ): RunningProcess {
        val pb =
            ProcessBuilder(cmd)
                .directory(workingDir)
                .redirectErrorStream(false)
        val p = pb.start()
        return JdkRunningProcess(p)
    }
}

private class JdkRunningProcess(
    private val process: Process,
) : RunningProcess {
    override fun stdoutStream() = process.inputStream

    override fun stderrStream() = process.errorStream

    override suspend fun awaitExit(): Int =
        withContext(Dispatchers.IO) {
            process.waitFor()
        }

    override fun killTree() {
        // JDK 9+ Process.descendants() reaches grandchildren (curl spawned by
        // python, ffmpeg spawned by curl, etc). destroyForcibly() on each so
        // a hung pipeline can't outlive the timeout.
        runCatching {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
