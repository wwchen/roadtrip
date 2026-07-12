package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.exceptions.FetchFailedException
import ca.floo.roadtrip.exceptions.FetchTimeoutException
import ca.floo.roadtrip.exceptions.TargetBusyException
import ca.floo.roadtrip.exceptions.TargetNotFoundException
import ca.floo.roadtrip.models.metadata.ingest.Phase
import ca.floo.roadtrip.models.metadata.ingest.RunKind
import ca.floo.roadtrip.models.metadata.ingest.RunOutcome
import ca.floo.roadtrip.models.metadata.ingest.Target
import ca.floo.roadtrip.repo.IngestRunRepo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.time.Duration

@OptIn(ExperimentalSerializationApi::class)
private val ingestControllerJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

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
    val etl: EtlOrchestrator,
    private val fetchTargets: Map<String, Target>,
    private val importTargets: Map<String, Target>,
    private val workingDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val processFactory: ProcessFactory = DefaultProcessFactory,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ingestRunRepo = IngestRunRepo(ctx)

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

    /** Fan-out targets for [kind]. Import order is the registry-derived execution order. */
    fun fanOutTargets(kind: RunKind): List<String> =
        when (kind) {
            RunKind.FETCH -> fetchTargets.keys.toList()
            RunKind.IMPORT -> importTargets.keys.toList()
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

        val parentId = ingestRunRepo.createParentRow(target.name, kind, triggeredBy)
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
            ingestRunRepo.completeParent(parentId)
            return RunOutcome(parentId, target.name, kind, "noop", null)
        }

        for (phase in phases) {
            val phaseId = ingestRunRepo.createPhaseRow(parentId, target.name, phase)
            try {
                val counts =
                    when (phase) {
                        is Phase.Fetch -> runFetch(phase)
                        is Phase.Import -> runImport(phase)
                    }
                ingestRunRepo.completePhase(phaseId, counts)
                log.info("ingest_runs id={} phase={} completed", phaseId, phase.label)
            } catch (e: Throwable) {
                val (notes, exit) = phaseFailureNotes(e)
                recordPhaseFailure(parentId, phaseId, phase, notes, exit)
                return RunOutcome(parentId, target.name, kind, "failed", phase.label)
            }
        }
        ingestRunRepo.completeParent(parentId)
        return RunOutcome(parentId, target.name, kind, "completed", null)
    }

    private fun recordPhaseFailure(
        parentId: Long,
        phaseId: Long,
        phase: Phase,
        notes: String,
        exitCode: Int?,
    ) {
        val shortNotes = truncateFailureNotes(notes)
        val phaseRecorded =
            runCatching { ingestRunRepo.failPhase(phaseId, shortNotes, exitCode) }
                .onFailure { failure ->
                    log.error(
                        "ingest_runs id={} phase={} failed but phase failure row could not be recorded: {}",
                        phaseId,
                        phase.label,
                        failure.message,
                        failure,
                    )
                }.isSuccess
        val parentRecorded = recordParentFailure(parentId, "phase=${phase.label}: $notes", phase.label)
        if (phaseRecorded && parentRecorded) {
            log.warn("ingest_runs id={} phase={} failed: {}", phaseId, phase.label, shortNotes)
        } else {
            log.warn(
                "ingest_runs id={} phase={} failed; failure persistence incomplete: {}",
                phaseId,
                phase.label,
                shortNotes,
            )
        }
    }

    private fun recordParentFailure(
        parentId: Long,
        notes: String,
        context: String,
    ): Boolean =
        runCatching { ingestRunRepo.failParent(parentId, truncateFailureNotes(notes)) }
            .onFailure { failure ->
                log.error(
                    "ingest_runs id={} {} failed but parent failure row could not be recorded: {}",
                    parentId,
                    context,
                    failure.message,
                    failure,
                )
            }.isSuccess

    private fun truncateFailureNotes(notes: String): String = notes.take(FAILURE_NOTES_MAX_CHARS)

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
            JSONB.valueOf(ingestControllerJson.encodeToString(FetchPhaseCountsDto(exitCode = 0)))
        }

    // -- Import phases (data/raw/ + data/etl-out/ → Postgres) -----------------
    //
    // The phase carries a row's display name + which YAML section it
    // came from. poi_data / campsite_data walk an ETL chain and write
    // canonical rows; campsite_parent_joiner runs a vendor-scoped
    // reconciliation pass that reparents campsites via runJoiner.
    //
    // Each branch writes a section-specific counts DTO so dashboards can
    // render whichever fields are populated; the legacy `seen`/`swept`/
    // `import_run_id` fields stay non-null only on the POI_DATA branch.
    private suspend fun runImport(phase: Phase.Import): JSONB =
        withContext(ioDispatcher) {
            when (phase.section) {
                Phase.Import.Section.POI_DATA -> {
                    val stats = etl.runPoiData(phase.name)
                    JSONB.valueOf(
                        ingestControllerJson.encodeToString(
                            ImportPhaseCountsDto(
                                importRunId = stats.upsertResult.runId,
                                seen = stats.upsertResult.seenCount,
                                swept = stats.upsertResult.sweptCount,
                                terminalEtl = stats.terminalEtlSlug,
                            ),
                        ),
                    )
                }
                Phase.Import.Section.CAMPSITE_DATA -> {
                    val stats = etl.runCampsiteData(phase.name)
                    JSONB.valueOf(
                        ingestControllerJson.encodeToString(
                            ImportPhaseCountsDto(
                                importRunId = stats.runId,
                                seen = stats.parsed,
                                swept = stats.swept,
                                terminalEtl = stats.terminalEtlSlug,
                                upsertedCampsites = stats.upserted,
                                skippedCampsites = stats.skipped,
                            ),
                        ),
                    )
                }
                Phase.Import.Section.CAMPSITE_PARENT_JOINER -> {
                    val stats = etl.runJoiner(phase.name)
                    JSONB.valueOf(
                        ingestControllerJson.encodeToString(
                            ImportPhaseCountsDto(
                                importRunId = -1L,
                                seen = stats.linksDiscovered,
                                swept = stats.staleLinksDeleted,
                                terminalEtl = stats.adapter,
                                createdLinks = stats.linksInserted,
                                staleLinksDeleted = stats.staleLinksDeleted,
                            ),
                        ),
                    )
                }
            }
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
        private const val FAILURE_NOTES_MAX_CHARS = 300
    }
}

@Serializable
private data class FetchPhaseCountsDto(
    @SerialName("exit_code") val exitCode: Int,
)

/**
 * Counts written into `ingest_runs.counts` (JSONB) for one import phase.
 * Section-specific fields are nullable; readers ignore the ones they
 * don't care about. Existing dashboards keyed off `seen`/`swept`/
 * `import_run_id` keep working. Campsite phases carry their campsite
 * import run id; joiner phases carry created_links / stale_links_deleted.
 */
@Serializable
private data class ImportPhaseCountsDto(
    @SerialName("import_run_id") val importRunId: Long,
    val seen: Int,
    val swept: Int,
    @SerialName("terminal_etl") val terminalEtl: String,
    @SerialName("upserted_campsites") val upsertedCampsites: Int? = null,
    @SerialName("skipped_campsites") val skippedCampsites: Int? = null,
    @SerialName("created_links") val createdLinks: Int? = null,
    @SerialName("stale_links_deleted") val staleLinksDeleted: Int? = null,
)
