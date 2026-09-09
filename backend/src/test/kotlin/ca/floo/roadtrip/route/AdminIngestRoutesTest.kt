package ca.floo.roadtrip.route

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.model.domain.PlanetFitnessLocationUpsertCandidate
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.model.metadata.ingest.Phase
import ca.floo.roadtrip.model.metadata.ingest.RunKind
import ca.floo.roadtrip.model.metadata.ingest.Target
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.route.api.admin.adminIngestRoutes
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.FlushCounts
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TerminalEtlBinding
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.terminalSink
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun `POST fetch routes are not registered`() =
        testApplication {
            val controller = controllerWith(emptyMap())
            application { routing { adminIngestRoutes(controller) } }

            assertEquals(HttpStatusCode.NotFound, client.post("/api/admin/data/fetch").status)
            assertEquals(HttpStatusCode.NotFound, client.post("/api/admin/data/fetch/t").status)
        }

    @Test
    fun `POST import fan-out preserves controller target order`() =
        testApplication {
            val controller =
                controllerWith(
                    linkedMapOf(
                        "Washington State Parks" to Target("Washington State Parks", emptyList()),
                        "Washington Aspira Resources" to Target("Washington Aspira Resources", emptyList()),
                        "Aspira Resources → Aspira Pins" to Target("Aspira Resources → Aspira Pins", emptyList()),
                    ),
                )
            application { routing { adminIngestRoutes(controller) } }

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
    fun `POST import for target with no import phases is a noop completion`() =
        testApplication {
            val controller =
                controllerWith(
                    mapOf(
                        "t" to Target("t", emptyList()),
                    ),
                )
            application { routing { adminIngestRoutes(controller) } }

            val resp = client.post("/api/admin/data/import/t")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("noop", body["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST import for a target whose phase fails returns 500 with status failed`() =
        testApplication {
            val controller =
                controllerWith(
                    mapOf(
                        FAILING_TARGET to Target(FAILING_TARGET, listOf(Phase.Import(FAILING_PHASE, "absent-poi-data"))),
                    ),
                )
            application { routing { adminIngestRoutes(controller) } }

            val resp = client.post("/api/admin/data/import/$FAILING_TARGET")

            assertEquals(HttpStatusCode.InternalServerError, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("failed", body["status"]!!.jsonPrimitive.content)
            assertEquals(FAILING_PHASE, body["failed_phase"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST import for all targets reports a busy target as busy`() =
        testApplication {
            val gate = CountDownLatch(1)
            val release = CountDownLatch(1)
            val controller = blockingController(gate, release)
            application { routing { adminIngestRoutes(controller) } }

            coroutineScope {
                val running = async(Dispatchers.IO) { controller.startRun(BUSY_TARGET, RunKind.IMPORT, "test") }
                assertTrue(gate.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS), "first import did not start")

                val resp = client.post("/api/admin/data/import")

                assertEquals(HttpStatusCode.InternalServerError, resp.status)
                val outcomes =
                    Json
                        .parseToJsonElement(resp.bodyAsText())
                        .jsonObject["outcomes"]!!
                        .jsonArray
                val busy = outcomes.single { it.jsonObject["target"]!!.jsonPrimitive.content == BUSY_TARGET }
                assertEquals("busy", busy.jsonObject["status"]!!.jsonPrimitive.content)

                release.countDown()
                running.await()
            }
        }

    @Test
    fun `GET runs filters by target`() =
        testApplication {
            val controller =
                controllerWith(
                    mapOf(
                        "alpha" to Target("alpha", emptyList()),
                        "beta" to Target("beta", emptyList()),
                    ),
                )
            application { routing { adminIngestRoutes(controller) } }

            client.post("/api/admin/data/import/alpha")
            client.post("/api/admin/data/import/beta")

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
                        "alpha" to Target("alpha", listOf(Phase.Import("k", "x"))),
                        "beta" to Target("beta", listOf(Phase.Import("k", "x"))),
                    ),
                )
            application { routing { adminIngestRoutes(controller) } }

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
            application { routing { adminIngestRoutes(controller) } }

            val resp = client.post("/api/admin/etl/catalog-match")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    private fun controllerWith(
        targets: Map<String, Target>,
        etl: EtlOrchestrator =
            EtlOrchestrator(
                ctx = ctx,
                rawDir = File("/tmp"),
                poiRegistry = PoiRegistry(emptyList(), emptyList()),
                staticDir = File("/tmp"),
            ),
    ): IngestController =
        IngestController(
            ctx = ctx,
            etl = etl,
            importTargets = targets,
            ioDispatcher = Dispatchers.IO,
        )

    // A single-target controller whose only import phase parks inside the ETL, so a
    // second run of that target hits TargetBusyException while the route fans out.
    private fun blockingController(
        gate: CountDownLatch,
        release: CountDownLatch,
    ): IngestController =
        controllerWith(
            mapOf(BUSY_TARGET to Target(BUSY_TARGET, listOf(Phase.Import("import:$BUSY_TARGET", BUSY_TARGET)))),
            etl =
                EtlOrchestrator(
                    ctx = ctx,
                    rawDir = File("/tmp"),
                    poiRegistry =
                        PoiRegistry(
                            dataSources = emptyList(),
                            poiData =
                                listOf(
                                    PoiDataEntry(
                                        name = BUSY_TARGET,
                                        category = "planet-fitness",
                                        etls = listOf(EtlEntry(slug = BUSY_ETL_SLUG, adapter = "BlockingEtl")),
                                    ),
                                ),
                        ),
                    staticDir = File("/tmp"),
                    etlRegistry =
                        mapOf(
                            BUSY_ETL_SLUG to
                                TerminalEtlBinding(
                                    etl = BlockingEtl(BUSY_ETL_SLUG, gate, release),
                                    sink = terminalSink { FlushCounts() },
                                ),
                        ),
                ),
        )

    private class BlockingEtl(
        override val etlSlug: String,
        private val gate: CountDownLatch,
        private val release: CountDownLatch,
    ) : SourceEtl<Unit, PlanetFitnessLocationUpsertCandidate> {
        override fun parse(inputs: InputBundle): Sequence<ParseResult<Unit>> =
            sequence {
                gate.countDown()
                check(release.await(GATE_TIMEOUT_SEC, TimeUnit.SECONDS)) { "release gate timed out" }
                yield(ParseResult.Ok(Unit))
            }

        override fun transform(
            dto: Unit,
            ctx: TransformCtx,
        ): Sequence<TransformResult<PlanetFitnessLocationUpsertCandidate>> = emptySequence()
    }

    private companion object {
        const val FAILING_TARGET = "failing"
        const val FAILING_PHASE = "import:absent"
        const val BUSY_TARGET = "Blocking Import"
        const val BUSY_ETL_SLUG = "blocking-etl"
        const val GATE_TIMEOUT_SEC = 5L
    }
}
