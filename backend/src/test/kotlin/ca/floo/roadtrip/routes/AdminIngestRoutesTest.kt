package ca.floo.roadtrip.routes

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import ca.floo.roadtrip.db.generated.tables.Pois.Companion.POIS
import ca.floo.roadtrip.models.metadata.ingest.Phase
import ca.floo.roadtrip.models.metadata.ingest.Target
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.routes.api.admin.adminIngestRoutes
import ca.floo.roadtrip.service.etl.framework.EtlOrchestrator
import ca.floo.roadtrip.service.etl.framework.IngestController
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
import java.io.File
import kotlin.test.assertEquals

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
            application { routing { adminIngestRoutes(controller, ctx) } }

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
            application { routing { adminIngestRoutes(controller, ctx) } }

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
            application { routing { adminIngestRoutes(controller, ctx) } }

            val resp = client.post("/api/admin/data/import/t")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("noop", body["status"]!!.jsonPrimitive.content)
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
            application { routing { adminIngestRoutes(controller, ctx) } }

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
            application { routing { adminIngestRoutes(controller, ctx) } }

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
            application { routing { adminIngestRoutes(controller, ctx) } }

            val resp = client.post("/api/admin/etl/catalog-match")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    private fun controllerWith(
        targets: Map<String, Target>,
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
            importTargets = targets,
            ioDispatcher = Dispatchers.IO,
        )
}
