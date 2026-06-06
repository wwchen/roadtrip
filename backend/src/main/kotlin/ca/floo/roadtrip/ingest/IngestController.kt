package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.importer.Importer
import ca.floo.roadtrip.importer.sourceFor
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

data class RunOutcome(
    val parentRunId: Long,
    val status: String, // 'completed' | 'failed'
    val failedPhase: String?,
)

// Per-target locked, structured-record orchestrator. RFC 0004 / issue #44.
//
// Sequence per startRun():
//   1. tryLock the target's mutex; on contention, throw TargetBusyException
//      carrying the existing parent run_id so the caller can return 409.
//   2. Insert a parent ingest_runs row (phase_kind='target', status='started').
//   3. For each phase in order, insert a phase row, run it, finalize the row.
//   4. On any phase failure, mark parent failed and skip remaining phases.
//   5. On success, mark parent completed.
//   6. Always release the mutex in finally.
//
// startRun is suspending and returns when the entire run finishes (sync POST
// is the default). Callers that want fire-and-forget wrap in scope.async.
class IngestController(
    private val ctx: DSLContext,
    private val importer: Importer,
    private val dataDir: File,
    private val targets: Map<String, Target>,
    private val workingDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val processFactory: ProcessFactory = DefaultProcessFactory,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks: Map<String, Mutex> = targets.mapValues { Mutex() }

    // Active parent run_id per target while held — populated under the mutex.
    // Returned in TargetBusyException so the admin route can put the existing
    // run_id in the 409 body instead of the caller having to chase it down.
    private val active: MutableMap<String, Long> = mutableMapOf()

    fun knownTargets(): Set<String> = targets.keys

    suspend fun startRun(
        targetName: String,
        triggeredBy: String,
    ): RunOutcome {
        val target = targets[targetName] ?: throw TargetNotFoundException(targetName)
        val mutex = locks[targetName]!!

        if (!mutex.tryLock()) {
            val existing =
                synchronized(active) { active[targetName] }
                    ?: error("mutex held but no active run_id for target=$targetName")
            throw TargetBusyException(targetName, existing)
        }

        val parentId = createParentRow(target.name, triggeredBy)
        synchronized(active) { active[targetName] = parentId }
        log.info("ingest_runs id={} target={} started ({} phases)", parentId, target.name, target.phases.size)

        try {
            return runPhases(target, parentId)
        } finally {
            synchronized(active) { active.remove(targetName) }
            mutex.unlock()
        }
    }

    private suspend fun runPhases(
        target: Target,
        parentId: Long,
    ): RunOutcome {
        for (phase in target.phases) {
            val phaseId = createPhaseRow(parentId, target.name, phase)
            try {
                val counts =
                    when (phase) {
                        is Phase.Shell -> runShell(phase)
                        is Phase.Kotlin -> runKotlin(phase)
                    }
                completePhase(phaseId, counts)
                log.info("ingest_runs id={} phase={} completed", phaseId, phase.label)
            } catch (e: Throwable) {
                val (notes, exit) = phaseFailureNotes(e)
                failPhase(phaseId, notes, exit)
                failParent(parentId, "phase=${phase.label}: ${notes.take(300)}")
                log.warn("ingest_runs id={} phase={} failed: {}", phaseId, phase.label, notes.take(300))
                return RunOutcome(parentId, "failed", phase.label)
            }
        }
        completeParent(parentId)
        return RunOutcome(parentId, "completed", null)
    }

    // -- Shell phases ---------------------------------------------------------
    private suspend fun runShell(phase: Phase.Shell): JSONB =
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
                throw ShellTimeoutException("phase ${phase.label} exceeded ${phase.timeoutSec}s timeout")
            }
            // Wait for drainers to finish so notes carry the full tail.
            runCatching { stdoutDrain.await() }
            runCatching { stderrDrain.await() }

            if (finished != 0) {
                throw ShellFailedException(
                    exitCode = finished,
                    stderrTail = synchronized(stderrTail) { stderrTail.toString() },
                )
            }
            JSONB.valueOf("""{"exit_code":0}""")
        }

    // -- Kotlin phases --------------------------------------------------------
    private suspend fun runKotlin(phase: Phase.Kotlin): JSONB =
        withContext(ioDispatcher) {
            val source = sourceFor(phase.sourceName, dataDir)
            val result = importer.run(source)
            JSONB.valueOf(
                """{"import_run_id":${result.runId},"seen":${result.seenCount},"swept":${result.sweptCount}}""",
            )
        }

    // -- ingest_runs row CRUD -------------------------------------------------
    private fun createParentRow(
        target: String,
        triggeredBy: String,
    ): Long =
        ctx
            .insertInto(INGEST_RUNS)
            .set(INGEST_RUNS.TARGET, target)
            .set(INGEST_RUNS.PHASE, "target")
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
                is Phase.Shell -> "shell"
                is Phase.Kotlin -> "kotlin"
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
            is ShellFailedException ->
                "exit=${e.exitCode}\n${e.stderrTail.trim()}" to e.exitCode
            is ShellTimeoutException -> (e.message ?: "timeout") to null
            else -> "${e.javaClass.simpleName}: ${e.message ?: ""}" to null
        }

    companion object {
        const val STDERR_TAIL_BYTES = 4 * 1024
    }
}

class ShellFailedException(
    val exitCode: Int,
    val stderrTail: String,
) : RuntimeException("shell phase exited $exitCode")

class ShellTimeoutException(
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
