package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.metadata.ingest.RunKind
import ca.floo.roadtrip.models.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.models.metadata.registry.Fetcher
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistryTargetsTest {
    @Test
    fun `fetch fan-out follows data source dependency order`() {
        val registry =
            PoiRegistry(
                dataSources =
                    listOf(
                        source("recgov-campground-enrichment", dependsOn = listOf("recgov-campgrounds")),
                        source("recgov-campgrounds"),
                    ),
                poiData = emptyList(),
            )

        val fetchTargets = fetchTargetsFromRegistry(registry, File("/repo"))
        val controller =
            IngestController(
                ctx = DSL.using(SQLDialect.POSTGRES),
                etl = EtlOrchestrator(DSL.using(SQLDialect.POSTGRES), File("/tmp"), registry),
                fetchTargets = fetchTargets,
                importTargets = emptyMap(),
                workingDir = File("/repo"),
            )

        assertEquals(
            listOf("recgov-campgrounds", "recgov-campground-enrichment"),
            controller.fanOutTargets(RunKind.FETCH),
        )
    }

    @Test
    fun `fetch target preserves registry fetcher args`() {
        val registry =
            PoiRegistry(
                dataSources =
                    listOf(
                        source(
                            "recgov-campground-enrichment",
                            args =
                                linkedMapOf(
                                    "slug" to "recgov-campground-enrichment",
                                    "resume" to "true",
                                ),
                            timeoutSec = 7200,
                        ),
                    ),
                poiData = emptyList(),
            )

        val phase =
            fetchTargetsFromRegistry(registry, File("/repo"))
                .getValue("recgov-campground-enrichment")
                .fetchPhases
                .single()

        assertEquals(
            listOf("--slug", "recgov-campground-enrichment", "--resume", "true"),
            phase.cmd.takeLast(4),
        )
        assertEquals(7200, phase.timeoutSec)
    }

    private fun source(
        slug: String,
        dependsOn: List<String> = emptyList(),
        args: Map<String, String> = emptyMap(),
        timeoutSec: Long = 30 * 60,
    ): DataSourceEntry =
        DataSourceEntry(
            slug = slug,
            name = slug,
            dependsOn = dependsOn,
            fetcher =
                Fetcher(
                    executor = "python3",
                    filename = "scripts/$slug.py",
                    args = args,
                    timeoutSec = timeoutSec,
                    outputDirPrefix = "data/raw/$slug",
                ),
        )
}
