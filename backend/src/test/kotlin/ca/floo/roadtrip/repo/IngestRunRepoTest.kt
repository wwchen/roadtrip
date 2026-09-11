package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.etl.ImportPhaseCounts
import ca.floo.roadtrip.model.metadata.ingest.Phase
import ca.floo.roadtrip.model.metadata.ingest.RunKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TARGET = "recgov"
private const val TRIGGERED_BY = "admin-api"
private const val IMPORT_RUN_ID = 4321L
private const val SEEN = 120
private const val SWEPT = 3
private const val TERMINAL_ETL = "recgov-campsites"
private const val UPSERTED_CAMPSITES = 99
private const val SKIPPED_CAMPSITES = 2

@Suppress("TopLevelPropertyNaming")
private val STALE_AFTER: Duration = Duration.ofMinutes(30)

class IngestRunRepoTest : SharedDbTest() {
    private val repo by lazy { IngestRunRepo(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM ingest_runs")
    }

    @Test
    fun `completePhase writes the counts the dashboard reads`() {
        val parentId = repo.createParentRow(TARGET, RunKind.IMPORT, TRIGGERED_BY)
        val phaseId = repo.createPhaseRow(parentId, TARGET, Phase.Import("import:$TARGET", TARGET))

        repo.completePhase(
            phaseId,
            ImportPhaseCounts(
                importRunId = IMPORT_RUN_ID,
                seen = SEEN,
                swept = SWEPT,
                terminalEtl = TERMINAL_ETL,
                upsertedCampsites = UPSERTED_CAMPSITES,
                skippedCampsites = SKIPPED_CAMPSITES,
            ),
        )

        val counts = countsOf(phaseId).jsonObject
        assertEquals("completed", statusOf(phaseId))
        assertEquals(IMPORT_RUN_ID, counts["import_run_id"]!!.jsonPrimitive.long)
        assertEquals(SEEN, counts["seen"]!!.jsonPrimitive.int)
        assertEquals(SWEPT, counts["swept"]!!.jsonPrimitive.int)
        assertEquals(TERMINAL_ETL, counts["terminal_etl"]!!.jsonPrimitive.content)
        assertEquals(UPSERTED_CAMPSITES, counts["upserted_campsites"]!!.jsonPrimitive.int)
        assertEquals(SKIPPED_CAMPSITES, counts["skipped_campsites"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an absent campsite count is omitted, not written as null`() {
        val parentId = repo.createParentRow(TARGET, RunKind.IMPORT, TRIGGERED_BY)
        val phaseId = repo.createPhaseRow(parentId, TARGET, Phase.Import("import:$TARGET", TARGET))

        repo.completePhase(
            phaseId,
            ImportPhaseCounts(
                importRunId = IMPORT_RUN_ID,
                seen = SEEN,
                swept = SWEPT,
                terminalEtl = TERMINAL_ETL,
            ),
        )

        val counts = countsOf(phaseId).jsonObject
        assertNull(counts["upserted_campsites"], "a POI_DATA phase must not ship a null campsite count")
        assertNull(counts["skipped_campsites"])
    }

    @Test
    fun `boot recovery marks stale started rows as aborted and leaves fresh ones alone`() {
        val staleId = startedRowAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1))
        val recentId = startedRowAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))

        assertEquals(1, repo.abortStaleStartedRows(STALE_AFTER))

        assertEquals("aborted", statusOf(staleId))
        assertNotNull(completedAtOf(staleId))
        assertTrue(notesOf(staleId)!!.contains("boot recovery"))
        assertEquals("started", statusOf(recentId), "rows younger than the cutoff must be untouched")
    }

    private fun startedRowAt(startedAt: OffsetDateTime): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO ingest_runs (target, phase, phase_kind, status, started_at, triggered_by)
                VALUES (?, 'import', 'target', 'started', ?::timestamptz, ?)
                RETURNING id
                """.trimIndent(),
                TARGET,
                startedAt,
                TRIGGERED_BY,
            )!!
            .get("id", Long::class.java)

    private fun countsOf(id: Long) =
        Json.parseToJsonElement(
            ctx.fetchOne("SELECT counts::text AS counts FROM ingest_runs WHERE id = ?", id)!!.get("counts", String::class.java),
        )

    private fun statusOf(id: Long): String? =
        ctx.fetchOne("SELECT status FROM ingest_runs WHERE id = ?", id)?.get("status", String::class.java)

    private fun notesOf(id: Long): String? =
        ctx.fetchOne("SELECT notes FROM ingest_runs WHERE id = ?", id)?.get("notes", String::class.java)

    private fun completedAtOf(id: Long): OffsetDateTime? =
        ctx.fetchOne("SELECT completed_at FROM ingest_runs WHERE id = ?", id)?.get("completed_at", OffsetDateTime::class.java)
}
