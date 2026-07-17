package ca.floo.roadtrip.routes

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.models.metadata.ingest.Phase
import ca.floo.roadtrip.models.metadata.ingest.Target
import ca.floo.roadtrip.repo.AdminIngestReadRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.etl.framework.ProcessFactory
import ca.floo.roadtrip.service.etl.framework.RunningProcess
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminIngestRoutesTest : SharedDbTest() {
    @BeforeEach
    fun reset() {
        ctx.deleteFrom(POIS).execute()
        ctx.deleteFrom(INGEST_RUNS).where(INGEST_RUNS.PARENT_RUN_ID.isNotNull).execute()
        ctx.deleteFrom(INGEST_RUNS).execute()
    }

    @Test
    fun `POST fetch unknown target returns 404 with known list`() =
        testApplication {
            val controller = controllerWith(emptyMap())
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            val resp = client.post("/api/admin/data/fetch/bad%22target")
            assertEquals(HttpStatusCode.NotFound, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("unknown target", body["error"]!!.jsonPrimitive.content)
            assertEquals("bad\"target", body["target"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST fetch happy path returns 200 and run shows in GET runs`() =
        testApplication {
            val factory = SingleProcessFactory(0, "ok\n", "")
            val controller =
                controllerWith(
                    mapOf(
                        "t" to
                            Target(
                                "t",
                                listOf(Phase.Fetch("step1", listOf("echo", "ok"))),
                                emptyList(),
                            ),
                    ),
                    factory = factory,
                )
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            val resp = client.post("/api/admin/data/fetch/t")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("completed", body["status"]!!.jsonPrimitive.content)
            assertEquals("t", body["target"]!!.jsonPrimitive.content)
            assertEquals("fetch", body["kind"]!!.jsonPrimitive.content)
            val runId = body["run_id"]!!.jsonPrimitive.content.toLong()

            val list = client.get("/api/admin/data/runs")
            assertEquals(HttpStatusCode.OK, list.status)
            val listBody = Json.parseToJsonElement(list.bodyAsText()).jsonObject
            val runs = listBody["runs"]!!.jsonArray
            assertTrue(runs.isNotEmpty(), "runs list must be non-empty")
            assertEquals("fetch", runs[0].jsonObject["kind"]!!.jsonPrimitive.content)

            val detail = client.get("/api/admin/data/runs/$runId")
            assertEquals(HttpStatusCode.OK, detail.status)
            val detailBody = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
            val phases = detailBody["phases"]!!.jsonArray
            assertEquals(1, phases.size)
            assertEquals("fetch", detailBody["kind"]!!.jsonPrimitive.content)
            assertEquals(
                "0",
                phases[0]
                    .jsonObject["counts"]!!
                    .jsonObject["exit_code"]!!
                    .jsonPrimitive
                    .content,
            )
        }

    @Test
    fun `POST fetch failure returns 500 with failed_phase`() =
        testApplication {
            val factory = SingleProcessFactory(7, "", "broke\n")
            val controller =
                controllerWith(
                    mapOf(
                        "t" to
                            Target(
                                "t",
                                listOf(Phase.Fetch("step1", listOf("false"))),
                                emptyList(),
                            ),
                    ),
                    factory = factory,
                )
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            val resp = client.post("/api/admin/data/fetch/t")
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("failed", body["status"]!!.jsonPrimitive.content)
            assertEquals("step1", body["failed_phase"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST fetch with no target fans out across all known targets`() =
        testApplication {
            val factory =
                MultiProcessFactory(
                    listOf(
                        FakeOutcome(0, "", ""),
                        FakeOutcome(0, "", ""),
                    ),
                )
            val controller =
                controllerWith(
                    mapOf(
                        "alpha" to Target("alpha", listOf(Phase.Fetch("a", listOf("a"))), emptyList()),
                        "beta" to Target("beta", listOf(Phase.Fetch("b", listOf("b"))), emptyList()),
                    ),
                    factory = factory,
                )
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            val resp = client.post("/api/admin/data/fetch")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("fetch", body["kind"]!!.jsonPrimitive.content)
            val outcomes = body["outcomes"]!!.jsonArray
            assertEquals(2, outcomes.size)
            assertEquals(
                setOf("alpha", "beta"),
                outcomes.map { it.jsonObject["target"]!!.jsonPrimitive.content }.toSet(),
            )
            assertTrue(outcomes.all { it.jsonObject["status"]!!.jsonPrimitive.content == "completed" })
        }

    @Test
    fun `POST import fan-out preserves controller target order`() =
        testApplication {
            val controller =
                controllerWith(
                    linkedMapOf(
                        "Washington State Parks" to Target("Washington State Parks", emptyList(), emptyList()),
                        "Washington Aspira Resources" to Target("Washington Aspira Resources", emptyList(), emptyList()),
                        "Aspira Resources → Aspira Pins" to Target("Aspira Resources → Aspira Pins", emptyList(), emptyList()),
                    ),
                )
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            val resp = client.post("/api/admin/data/import")

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val outcomes = body["outcomes"]!!.jsonArray
            assertEquals(
                listOf("Washington State Parks", "Washington Aspira Resources", "Aspira Resources → Aspira Pins"),
                outcomes.map { it.jsonObject["target"]!!.jsonPrimitive.content },
            )
        }

    @Test
    fun `POST import for a fetch-only target is a noop completion`() =
        testApplication {
            // tesla-pricing-shaped target: fetch phases, no import phases.
            // POSTing to /import/{target} must return 200 with status='noop'
            // rather than 500, because there's nothing to import.
            val controller =
                controllerWith(
                    mapOf(
                        "t" to
                            Target(
                                "t",
                                listOf(Phase.Fetch("f", listOf("a"))),
                                emptyList(),
                            ),
                    ),
                )
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            val resp = client.post("/api/admin/data/import/t")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("noop", body["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET runs filters by target`() =
        testApplication {
            val factory =
                MultiProcessFactory(
                    listOf(
                        FakeOutcome(0, "", ""),
                        FakeOutcome(0, "", ""),
                    ),
                )
            val controller =
                controllerWith(
                    mapOf(
                        "alpha" to Target("alpha", listOf(Phase.Fetch("a", listOf("a"))), emptyList()),
                        "beta" to Target("beta", listOf(Phase.Fetch("b", listOf("b"))), emptyList()),
                    ),
                    factory = factory,
                )
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            client.post("/api/admin/data/fetch/alpha")
            client.post("/api/admin/data/fetch/beta")

            val onlyAlpha = client.get("/api/admin/data/runs?target=alpha")
            val body = Json.parseToJsonElement(onlyAlpha.bodyAsText()).jsonObject
            val runs = body["runs"]!!.jsonArray
            assertEquals(1, runs.size)
            assertEquals("alpha", runs[0].jsonObject["target"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET status includes every known target`() =
        testApplication {
            val controller =
                controllerWith(
                    mapOf(
                        "alpha" to Target("alpha", emptyList(), listOf(Phase.Import("k", "x"))),
                        "beta" to Target("beta", emptyList(), listOf(Phase.Import("k", "x"))),
                    ),
                )
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            val resp = client.get("/api/admin/data/status")
            assertEquals(HttpStatusCode.OK, resp.status)
            val targets =
                Json
                    .parseToJsonElement(resp.bodyAsText())
                    .jsonObject["targets"]!!
                    .jsonArray
            assertEquals(2, targets.size)
            assertEquals(
                setOf("alpha", "beta"),
                targets.map { it.jsonObject["target"]!!.jsonPrimitive.content }.toSet(),
            )
        }

    @Test
    fun `POST catalog-match is not registered`() =
        testApplication {
            val controller = controllerWith(emptyMap())
            application { routing { adminIngestRoutes(controller, AdminIngestReadRepo(ctx)) } }

            val resp = client.post("/api/admin/etl/catalog-match")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    private fun controllerWith(
        targets: Map<String, Target>,
        factory: ProcessFactory = NoProcessFactory,
        etl: EtlOrchestrator =
            EtlOrchestrator(
                ctx,
                File("/tmp"),
                ca.floo.roadtrip.models.metadata.registry
                    .PoiRegistry(emptyList(), emptyList()),
            ),
    ): IngestController =
        IngestController(
            ctx = ctx,
            etl = etl,
            fetchTargets = targets,
            importTargets = targets,
            workingDir = File("/tmp"),
            ioDispatcher = Dispatchers.IO,
            processFactory = factory,
        )

    private object NoProcessFactory : ProcessFactory {
        override fun start(
            cmd: List<String>,
            workingDir: File,
        ): RunningProcess = error("no process expected")
    }

    private data class FakeOutcome(
        val exit: Int,
        val stdout: String,
        val stderr: String,
    )

    private class SingleProcessFactory(
        exit: Int,
        stdout: String,
        stderr: String,
    ) : ProcessFactory {
        private val outcome = FakeOutcome(exit, stdout, stderr)

        override fun start(
            cmd: List<String>,
            workingDir: File,
        ): RunningProcess = SimpleProcess(outcome)
    }

    private class MultiProcessFactory(
        outcomes: List<FakeOutcome>,
    ) : ProcessFactory {
        private val queue: ArrayDeque<FakeOutcome> = ArrayDeque(outcomes)

        override fun start(
            cmd: List<String>,
            workingDir: File,
        ): RunningProcess = SimpleProcess(queue.removeFirstOrNull() ?: error("no outcome queued for $cmd"))
    }

    private class SimpleProcess(
        private val outcome: FakeOutcome,
    ) : RunningProcess {
        override fun stdoutStream(): InputStream = ByteArrayInputStream(outcome.stdout.toByteArray())

        override fun stderrStream(): InputStream = ByteArrayInputStream(outcome.stderr.toByteArray())

        override suspend fun awaitExit(): Int = outcome.exit

        override fun killTree() {}
    }
}
