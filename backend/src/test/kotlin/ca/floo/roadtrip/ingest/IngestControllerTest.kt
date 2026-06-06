package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.importer.Importer
import ca.floo.roadtrip.importer.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestControllerTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    @BeforeAll
    fun startPg() {
        System.setProperty("api.version", "1.44")
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("ingest_test")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        val cfg =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
                maximumPoolSize = 4
            }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = DSL.using(ds, SQLDialect.POSTGRES)
    }

    @AfterAll
    fun stopPg() {
        ds.close()
        pg.stop()
    }

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
            runBlocking { controller.startRun("nope", "test") }
        }
    }

    @Test
    fun `single shell phase happy path records completed parent and phase rows`() =
        runBlocking {
            val factory = FakeProcessFactory()
            factory.queue(FakeProcess(stdout = "ok\n", stderr = "", exit = 0))
            val controller =
                controllerWith(
                    mapOf("t" to Target("t", listOf(Phase.Shell("step1", listOf("echo", "ok"))))),
                    factory = factory,
                )

            val outcome = controller.startRun("t", "test")

            assertEquals("completed", outcome.status)
            assertNull(outcome.failedPhase)

            val parent = ctx.selectFrom(INGEST_RUNS).where(INGEST_RUNS.ID.eq(outcome.parentRunId)).fetchOne()!!
            assertEquals("target", parent.phaseKind)
            assertEquals("completed", parent.status)
            assertNotNull(parent.completedAt)

            val phases =
                ctx
                    .selectFrom(INGEST_RUNS)
                    .where(INGEST_RUNS.PARENT_RUN_ID.eq(outcome.parentRunId))
                    .fetch()
            assertEquals(1, phases.size)
            assertEquals("shell", phases[0].phaseKind)
            assertEquals("completed", phases[0].status)
        }

    @Test
    fun `shell non-zero exit fails phase and parent`() =
        runBlocking {
            val factory = FakeProcessFactory()
            factory.queue(FakeProcess(stdout = "", stderr = "boom\n", exit = 1))
            val controller =
                controllerWith(
                    mapOf("t" to Target("t", listOf(Phase.Shell("step1", listOf("false"))))),
                    factory = factory,
                )

            val outcome = controller.startRun("t", "test")

            assertEquals("failed", outcome.status)
            assertEquals("step1", outcome.failedPhase)

            val parent = ctx.selectFrom(INGEST_RUNS).where(INGEST_RUNS.ID.eq(outcome.parentRunId)).fetchOne()!!
            assertEquals("failed", parent.status)
            assertTrue(parent.notes!!.contains("step1"), "parent notes should mention failing phase")

            val phase = ctx.selectFrom(INGEST_RUNS).where(INGEST_RUNS.PARENT_RUN_ID.eq(outcome.parentRunId)).fetchOne()!!
            assertEquals("failed", phase.status)
            assertEquals(1, phase.exitCode)
            assertTrue(phase.notes!!.contains("boom"), "phase notes should carry stderr tail")
        }

    @Test
    fun `phase 2 failure means phase 3 is never created`() =
        runBlocking {
            val factory = FakeProcessFactory()
            factory.queue(FakeProcess(stdout = "", stderr = "", exit = 0)) // phase 1 ok
            factory.queue(FakeProcess(stdout = "", stderr = "phase 2 broke\n", exit = 7))
            // phase 3 is queued but should never be consumed
            factory.queue(FakeProcess(stdout = "", stderr = "", exit = 0))
            val controller =
                controllerWith(
                    mapOf(
                        "t" to
                            Target(
                                "t",
                                listOf(
                                    Phase.Shell("p1", listOf("a")),
                                    Phase.Shell("p2", listOf("b")),
                                    Phase.Shell("p3", listOf("c")),
                                ),
                            ),
                    ),
                    factory = factory,
                )

            val outcome = controller.startRun("t", "test")

            assertEquals("failed", outcome.status)
            assertEquals("p2", outcome.failedPhase)

            val phases =
                ctx
                    .selectFrom(INGEST_RUNS)
                    .where(INGEST_RUNS.PARENT_RUN_ID.eq(outcome.parentRunId))
                    .orderBy(INGEST_RUNS.ID.asc())
                    .fetch()
            assertEquals(2, phases.size, "phase 3 must not have been created")
            assertEquals("p1", phases[0].phase)
            assertEquals("completed", phases[0].status)
            assertEquals("p2", phases[1].phase)
            assertEquals("failed", phases[1].status)
            assertEquals(1, factory.unused(), "phase 3 process must not be consumed")
        }

    @Test
    fun `concurrent same-target throws TargetBusyException with running run_id`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val factory = FakeProcessFactory()
            factory.queue(BlockingFakeProcess(gate, release))
            val controller =
                controllerWith(
                    mapOf("t" to Target("t", listOf(Phase.Shell("hold", listOf("sleep"))))),
                    factory = factory,
                )

            coroutineScope {
                val first = async(Dispatchers.IO) { controller.startRun("t", "first") }

                // Wait for the phase to actually start (process spawn => gate completes).
                withTimeout(5_000) { gate.await() }

                val ex =
                    assertThrows<TargetBusyException> {
                        runBlocking { controller.startRun("t", "second") }
                    }
                // Must surface the running parent run_id, not invent a new one.
                val running =
                    ctx
                        .selectFrom(INGEST_RUNS)
                        .where(INGEST_RUNS.TARGET.eq("t"))
                        .and(INGEST_RUNS.PHASE_KIND.eq("target"))
                        .and(INGEST_RUNS.STATUS.eq("started"))
                        .fetchOne()!!
                assertEquals(running.id, ex.runningRunId)

                release.complete(Unit)
                val outcome = first.await()
                assertEquals("completed", outcome.status)
            }
        }

    @Test
    fun `different targets run concurrently`() =
        runBlocking {
            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val factory = FakeProcessFactory()
            // Both processes block until release; if they serialized, only one
            // would hit its gate before the other releases.
            factory.queue(BlockingFakeProcess(gateA, release))
            factory.queue(BlockingFakeProcess(gateB, release))
            val controller =
                controllerWith(
                    mapOf(
                        "a" to Target("a", listOf(Phase.Shell("ha", listOf("sleep")))),
                        "b" to Target("b", listOf(Phase.Shell("hb", listOf("sleep")))),
                    ),
                    factory = factory,
                )

            coroutineScope {
                val ra = async(Dispatchers.IO) { controller.startRun("a", "test") }
                val rb = async(Dispatchers.IO) { controller.startRun("b", "test") }
                withTimeout(5_000) {
                    gateA.await()
                    gateB.await()
                }
                release.complete(Unit)
                assertEquals("completed", ra.await().status)
                assertEquals("completed", rb.await().status)
            }
        }

    @Test
    fun `mutex is released after failure so the next run can proceed`() =
        runBlocking {
            val factory = FakeProcessFactory()
            factory.queue(FakeProcess(stdout = "", stderr = "x\n", exit = 9))
            factory.queue(FakeProcess(stdout = "", stderr = "", exit = 0))
            val controller =
                controllerWith(
                    mapOf("t" to Target("t", listOf(Phase.Shell("only", listOf("a"))))),
                    factory = factory,
                )

            val first = controller.startRun("t", "test")
            assertEquals("failed", first.status)
            // Second run must not be 409 — mutex must have been released.
            val second = controller.startRun("t", "test")
            assertEquals("completed", second.status)
        }

    @Test
    fun `boot recovery marks stale started rows as aborted`() {
        // Hand-roll a 'started' parent row 1h in the past.
        val past = OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)
        val staleId =
            ctx
                .insertInto(INGEST_RUNS)
                .set(INGEST_RUNS.TARGET, "t")
                .set(INGEST_RUNS.PHASE, "target")
                .set(INGEST_RUNS.PHASE_KIND, "target")
                .set(INGEST_RUNS.STATUS, "started")
                .set(INGEST_RUNS.STARTED_AT, past)
                .set(INGEST_RUNS.TRIGGERED_BY, "admin-api")
                .returningResult(INGEST_RUNS.ID)
                .fetchOne()!!
                .value1()!!
        // And a 'started' row from 1 minute ago — must NOT be swept.
        val recent = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)
        val recentId =
            ctx
                .insertInto(INGEST_RUNS)
                .set(INGEST_RUNS.TARGET, "t")
                .set(INGEST_RUNS.PHASE, "target")
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

    @Test
    fun `kotlin phase failure surfaces as failed phase row`() {
        // sourceFor() throws IllegalStateException for an unknown source name;
        // IngestController catches it and records the failure on the phase row.
        val controller =
            controllerWith(
                mapOf("t" to Target("t", listOf(Phase.Kotlin("import:does-not-exist", "does-not-exist")))),
                dataDir = File("/tmp/this-does-not-exist-${System.nanoTime()}"),
            )

        val outcome = runBlocking { controller.startRun("t", "test") }
        assertEquals("failed", outcome.status)
        val phase =
            ctx
                .selectFrom(INGEST_RUNS)
                .where(INGEST_RUNS.PARENT_RUN_ID.eq(outcome.parentRunId))
                .fetchOne() ?: fail("phase row not created")
        assertEquals("failed", phase.status)
        assertNotNull(phase.notes)
    }

    private fun controllerWith(
        targets: Map<String, Target>,
        factory: FakeProcessFactory = FakeProcessFactory(),
        dataDir: File = File("/tmp"),
    ): IngestController =
        IngestController(
            ctx = ctx,
            importer = Importer(ctx),
            dataDir = dataDir,
            targets = targets,
            workingDir = File("/tmp"),
            ioDispatcher = Dispatchers.IO,
            processFactory = factory,
        )

    // -- Fakes ----------------------------------------------------------------

    private class FakeProcessFactory : ProcessFactory {
        private val queue: ArrayDeque<RunningProcess> = ArrayDeque()

        fun queue(p: RunningProcess) = queue.addLast(p)

        fun unused(): Int = queue.size

        override fun start(
            cmd: List<String>,
            workingDir: File,
        ): RunningProcess = queue.removeFirstOrNull() ?: error("no fake process queued for cmd=$cmd")
    }

    private class FakeProcess(
        stdout: String,
        stderr: String,
        private val exit: Int,
    ) : RunningProcess {
        private val out: InputStream = ByteArrayInputStream(stdout.toByteArray())
        private val err: InputStream = ByteArrayInputStream(stderr.toByteArray())

        override fun stdoutStream(): InputStream = out

        override fun stderrStream(): InputStream = err

        override suspend fun awaitExit(): Int = exit

        override fun killTree() {}
    }

    private class BlockingFakeProcess(
        private val gate: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : RunningProcess {
        override fun stdoutStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override fun stderrStream(): InputStream = ByteArrayInputStream(byteArrayOf())

        override suspend fun awaitExit(): Int {
            // Signal that the phase started, then wait for the test to release.
            gate.complete(Unit)
            release.await()
            // Spin briefly so awaitExit returns AFTER drainers finish.
            delay(10)
            return 0
        }

        override fun killTree() {}
    }
}
