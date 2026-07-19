package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.model.etl.PlanetFitnessLocationEtlOutput
import ca.floo.roadtrip.model.metadata.ValidationResult
import ca.floo.roadtrip.model.metadata.ingest.Phase
import ca.floo.roadtrip.model.metadata.ingest.RunKind
import ca.floo.roadtrip.model.metadata.ingest.Target
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.support.TargetBusyException
import ca.floo.roadtrip.support.TargetNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class IngestControllerTest : SharedDbTest() {
    @BeforeEach
    fun reset() {
        // Children first — FK from phase rows to parent rows.
        ctx.deleteFrom(POIS).execute()
        ctx.deleteFrom(INGEST_RUNS).where(INGEST_RUNS.PARENT_RUN_ID.isNotNull).execute()
        ctx.deleteFrom(INGEST_RUNS).execute()
    }

    @Test
    fun `unknown target throws`() {
        val controller = controllerWith(emptyMap())
        assertThrows<TargetNotFoundException> {
            runBlocking { controller.startRun("nope", RunKind.IMPORT, "test") }
        }
    }

    @Test
    fun `import-with-no-phases is a noop that completes`() =
        runBlocking {
            val controller = controllerWith(targetMap("curated"))

            val outcome = controller.startRun("curated", RunKind.IMPORT, "test")
            assertEquals("noop", outcome.status)
            assertEquals(RunKind.IMPORT, outcome.kind)

            val parent = ctx.selectFrom(INGEST_RUNS).where(INGEST_RUNS.ID.eq(outcome.parentRunId)).fetchOne()!!
            assertEquals("target", parent.phaseKind)
            assertEquals("import", parent.phase)
            assertEquals("completed", parent.status)
            assertNotNull(parent.completedAt)
            assertEquals(0, ctx.fetchCount(INGEST_RUNS, INGEST_RUNS.PARENT_RUN_ID.eq(outcome.parentRunId)))
        }

    @Test
    fun `import phase failure surfaces as failed phase row`() {
        val controller =
            controllerWith(
                targetMap("t", Phase.Import("import:does-not-exist", "does-not-exist")),
                dataDir = File("/tmp/this-does-not-exist-${System.nanoTime()}"),
            )

        val outcome = runBlocking { controller.startRun("t", RunKind.IMPORT, "test") }
        assertEquals("failed", outcome.status)
        assertEquals("import:does-not-exist", outcome.failedPhase)

        val parent = ctx.selectFrom(INGEST_RUNS).where(INGEST_RUNS.ID.eq(outcome.parentRunId)).fetchOne()!!
        assertEquals("failed", parent.status)
        assertTrue(parent.notes!!.contains("import:does-not-exist"), "parent notes should mention failing phase")

        val phase =
            ctx
                .selectFrom(INGEST_RUNS)
                .where(INGEST_RUNS.PARENT_RUN_ID.eq(outcome.parentRunId))
                .fetchOne() ?: fail("phase row not created")
        assertEquals("failed", phase.status)
        assertEquals("import", phase.phaseKind)
        assertNotNull(phase.notes)
        assertNull(phase.exitCode)
    }

    @Test
    fun `phase 2 failure means phase 3 is never created`() =
        runBlocking {
            val controller =
                controllerWith(
                    targetMap(
                        "t",
                        Phase.Import("p1", "first"),
                        Phase.Import("p2", "second"),
                        Phase.Import("p3", "third"),
                    ),
                )

            val outcome = controller.startRun("t", RunKind.IMPORT, "test")

            assertEquals("failed", outcome.status)
            assertEquals("p1", outcome.failedPhase)

            val phases =
                ctx
                    .selectFrom(INGEST_RUNS)
                    .where(INGEST_RUNS.PARENT_RUN_ID.eq(outcome.parentRunId))
                    .orderBy(INGEST_RUNS.ID.asc())
                    .fetch()
            assertEquals(1, phases.size, "later phases must not be created after failure")
            assertEquals("p1", phases[0].phase)
            assertEquals("failed", phases[0].status)
        }

    @Test
    fun `concurrent same-target import throws TargetBusyException with running run_id`() =
        runBlocking {
            val gate = CountDownLatch(1)
            val release = CountDownLatch(1)
            val registry = blockingRegistry("Blocking Import" to "blocking-etl")
            val controller =
                controllerWith(
                    targetMap("Blocking Import", Phase.Import("import:Blocking Import", "Blocking Import")),
                    registry = registry,
                    etlRegistry =
                        mapOf(
                            "blocking-etl" to BlockingPlanetFitnessEtl("blocking-etl", gate, release),
                        ),
                )

            coroutineScope {
                val first = async(Dispatchers.IO) { controller.startRun("Blocking Import", RunKind.IMPORT, "first") }
                assertTrue(gate.await(5, TimeUnit.SECONDS), "first import did not start")

                val ex =
                    assertThrows<TargetBusyException> {
                        runBlocking { controller.startRun("Blocking Import", RunKind.IMPORT, "second") }
                    }
                val running =
                    ctx
                        .selectFrom(INGEST_RUNS)
                        .where(INGEST_RUNS.TARGET.eq("Blocking Import"))
                        .and(INGEST_RUNS.PHASE_KIND.eq("target"))
                        .and(INGEST_RUNS.STATUS.eq("started"))
                        .fetchOne()!!
                assertEquals(running.id, ex.runningRunId)

                release.countDown()
                val outcome = first.await()
                assertEquals("completed", outcome.status)
            }
        }

    @Test
    fun `different targets run concurrently`() =
        runBlocking {
            val gate = CountDownLatch(2)
            val release = CountDownLatch(1)
            val registry =
                blockingRegistry(
                    "Blocking A" to "blocking-a",
                    "Blocking B" to "blocking-b",
                )
            val controller =
                controllerWith(
                    mapOf(
                        "Blocking A" to Target("Blocking A", listOf(Phase.Import("import:Blocking A", "Blocking A"))),
                        "Blocking B" to Target("Blocking B", listOf(Phase.Import("import:Blocking B", "Blocking B"))),
                    ),
                    registry = registry,
                    etlRegistry =
                        mapOf(
                            "blocking-a" to BlockingPlanetFitnessEtl("blocking-a", gate, release),
                            "blocking-b" to BlockingPlanetFitnessEtl("blocking-b", gate, release),
                        ),
                )

            coroutineScope {
                val first = async(Dispatchers.IO) { controller.startRun("Blocking A", RunKind.IMPORT, "test") }
                val second = async(Dispatchers.IO) { controller.startRun("Blocking B", RunKind.IMPORT, "test") }
                assertTrue(gate.await(5, TimeUnit.SECONDS), "both imports did not start concurrently")
                release.countDown()
                assertEquals("completed", first.await().status)
                assertEquals("completed", second.await().status)
            }
        }

    @Test
    fun `boot recovery marks stale started rows as aborted`() {
        val past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
        val staleId =
            ctx
                .insertInto(INGEST_RUNS)
                .set(INGEST_RUNS.TARGET, "t")
                .set(INGEST_RUNS.PHASE, "import")
                .set(INGEST_RUNS.PHASE_KIND, "target")
                .set(INGEST_RUNS.STATUS, "started")
                .set(INGEST_RUNS.STARTED_AT, past)
                .set(INGEST_RUNS.TRIGGERED_BY, "admin-api")
                .returningResult(INGEST_RUNS.ID)
                .fetchOne()!!
                .value1()!!
        val recent = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
        val recentId =
            ctx
                .insertInto(INGEST_RUNS)
                .set(INGEST_RUNS.TARGET, "t")
                .set(INGEST_RUNS.PHASE, "import")
                .set(INGEST_RUNS.PHASE_KIND, "target")
                .set(INGEST_RUNS.STATUS, "started")
                .set(INGEST_RUNS.STARTED_AT, recent)
                .set(INGEST_RUNS.TRIGGERED_BY, "admin-api")
                .returningResult(INGEST_RUNS.ID)
                .fetchOne()!!
                .value1()!!

        val swept = sweepStaleIngestRuns(ctx)
        assertEquals(1, swept)

        val staleAfter = ctx.selectFrom(INGEST_RUNS).where(INGEST_RUNS.ID.eq(staleId)).fetchOne()!!
        assertEquals("aborted", staleAfter.status)
        assertNotNull(staleAfter.completedAt)
        assertTrue(staleAfter.notes!!.contains("boot recovery"))

        val recentAfter = ctx.selectFrom(INGEST_RUNS).where(INGEST_RUNS.ID.eq(recentId)).fetchOne()!!
        assertEquals("started", recentAfter.status, "rows younger than the cutoff must be untouched")
    }

    private fun controllerWith(
        targets: Map<String, Target>,
        registry: PoiRegistry = PoiRegistry(emptyList(), emptyList()),
        dataDir: File = File("/tmp"),
        etlRegistry: Map<String, SourceEtl<*, *>> = emptyMap(),
    ): IngestController =
        IngestController(
            ctx = ctx,
            etl =
                EtlOrchestrator(
                    ctx = ctx,
                    rawDir = dataDir,
                    poiRegistry = registry,
                    staticDir = dataDir,
                    etlRegistry = etlRegistry,
                ),
            importTargets = targets,
            ioDispatcher = Dispatchers.IO,
        )

    private fun targetMap(
        name: String,
        vararg importPhases: Phase.Import,
    ): Map<String, Target> = mapOf(name to Target(name, importPhases.toList()))

    private fun blockingRegistry(vararg rows: Pair<String, String>): PoiRegistry =
        PoiRegistry(
            dataSources = emptyList(),
            poiData =
                rows.map { (name, slug) ->
                    PoiDataEntry(
                        name = name,
                        category = "planet-fitness",
                        etls = listOf(EtlEntry(slug = slug, adapter = "BlockingPlanetFitnessEtl")),
                    )
                },
        )

    private class BlockingPlanetFitnessEtl(
        override val etlSlug: String,
        private val gate: CountDownLatch,
        private val release: CountDownLatch,
    ) : SourceEtl<Unit, PlanetFitnessLocationEtlOutput> {
        override fun parse(inputs: InputBundle) {
            gate.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "release gate timed out" }
        }

        override fun validate(dto: Unit): ValidationResult<Unit> = ValidationResult.Ok(dto)

        override fun transform(
            dto: Unit,
            ctx: TransformCtx,
        ): PlanetFitnessLocationEtlOutput = PlanetFitnessLocationEtlOutput(emptyList())
    }
}
