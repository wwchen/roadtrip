package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.ingest.Phase
import ca.floo.roadtrip.model.metadata.ingest.RunKind
import ca.floo.roadtrip.model.metadata.ingest.RunOutcome
import ca.floo.roadtrip.model.metadata.ingest.Target
import ca.floo.roadtrip.observability.RoadtripMetrics
import ca.floo.roadtrip.repo.IngestRunRepo
import ca.floo.roadtrip.support.TargetBusyException
import ca.floo.roadtrip.support.TargetNotFoundException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory

// Terminal status reported to metrics when the run threw instead of resolving to
// a RunOutcome status ('completed' | 'failed' | 'noop').
private const val INGEST_STATUS_ERROR = "error"

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
//      phase='import', status='started').
//   3. For each import phase, insert a phase row, run it, finalize the row.
//   4. On any phase failure, mark parent failed and skip remaining phases.
//   5. On success, mark parent completed.
//   6. Always release the mutex in finally.
//
// startRun is suspending and returns when the entire run finishes (sync POST
// is the default). Callers that want fire-and-forget wrap in scope.async.
class IngestController(
    private val ctx: DSLContext,
    val etl: EtlOrchestrator,
    private val importTargets: Map<String, Target>,
    private val metrics: RoadtripMetrics = RoadtripMetrics.NoOp,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ingestRunRepo = IngestRunRepo(ctx)

    private val locks: Map<String, Mutex> = importTargets.keys.associateWith { Mutex() }

    private val active: MutableMap<String, Long> = mutableMapOf()

    fun knownTargets(): Set<String> = importTargets.keys

    /** Fan-out targets for [kind]. Import order is the registry-derived execution order. */
    fun fanOutTargets(kind: RunKind): List<String> =
        when (kind) {
            RunKind.IMPORT -> importTargets.keys.toList()
        }

    suspend fun startRun(
        targetName: String,
        kind: RunKind,
        triggeredBy: String,
    ): RunOutcome {
        val target = importTargets[targetName] ?: throw TargetNotFoundException(targetName)
        val mutex = locks[targetName]!!

        if (!mutex.tryLock()) {
            val existing =
                synchronized(active) { active[targetName] }
                    ?: error("mutex held but no active run_id for $targetName")
            throw TargetBusyException(targetName, existing)
        }

        val phases = target.importPhases

        val parentId = ingestRunRepo.createParentRow(target.name, kind, triggeredBy)
        synchronized(active) { active[targetName] = parentId }
        log.info(
            "ingest_runs id={} target={} kind={} started ({} phases)",
            parentId,
            target.name,
            kind.rowValue,
            phases.size,
        )

        try {
            return runPhases(target, kind, phases, parentId).also {
                metrics.ingestRunFinished(target = it.target, kind = kind.rowValue, status = it.status)
            }
        } catch (e: Throwable) {
            // A throw here is a run that never reached a terminal ingest_runs
            // status — the case a dashboard reading the table cannot see at all.
            metrics.ingestRunFinished(target = target.name, kind = kind.rowValue, status = INGEST_STATUS_ERROR)
            throw e
        } finally {
            synchronized(active) { active.remove(targetName) }
            mutex.unlock()
        }
    }

    private suspend fun runPhases(
        target: Target,
        kind: RunKind,
        phases: List<Phase.Import>,
        parentId: Long,
    ): RunOutcome {
        // Empty phase list is a legitimate no-op. Mark the parent completed and
        // return so it shows up cleanly on the dashboard rather than as a
        // phantom row.
        if (phases.isEmpty()) {
            ingestRunRepo.completeParent(parentId)
            return RunOutcome(parentId, target.name, kind, "noop", null)
        }

        for (phase in phases) {
            val phaseId = ingestRunRepo.createPhaseRow(parentId, target.name, phase)
            try {
                val counts = runImport(phase)
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

    // -- Import phases (data/raw/ + data/etl-out/ → Postgres) -----------------
    //
    // The phase carries a row's display name + which YAML section it
    // came from. poi_data / campsite_data walk an ETL chain and write
    // canonical rows.
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
            }
        }

    private fun phaseFailureNotes(e: Throwable): Pair<String, Int?> = "${e.javaClass.simpleName}: ${e.message ?: ""}" to null

    companion object {
        private const val FAILURE_NOTES_MAX_CHARS = 300
    }
}

/**
 * Counts written into `ingest_runs.counts` (JSONB) for one import phase.
 * Section-specific fields are nullable; readers ignore the ones they
 * don't care about. Existing dashboards keyed off `seen`/`swept`/
 * `import_run_id` keep working.
 */
@Serializable
private data class ImportPhaseCountsDto(
    @SerialName("import_run_id") val importRunId: Long,
    val seen: Int,
    val swept: Int,
    @SerialName("terminal_etl") val terminalEtl: String,
    @SerialName("upserted_campsites") val upsertedCampsites: Int? = null,
    @SerialName("skipped_campsites") val skippedCampsites: Int? = null,
)
