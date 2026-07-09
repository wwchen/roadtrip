package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.metadata.ingest.RunKind
import ca.floo.roadtrip.models.metadata.registry.CampsiteDataEntry
import ca.floo.roadtrip.models.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.models.metadata.registry.EtlEntry
import ca.floo.roadtrip.models.metadata.registry.Fetcher
import ca.floo.roadtrip.models.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.models.metadata.registry.PoiReservableJoinerEntry
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

    @Test
    fun `import fan-out omits disabled or unwired registry rows`() {
        val registry =
            PoiRegistry(
                dataSources = emptyList(),
                poiData =
                    listOf(
                        PoiDataEntry(
                            name = "Runnable Campgrounds",
                            category = "campground",
                            etls = listOf(EtlEntry(slug = "campflare-campgrounds", adapter = "CampflareCampgroundsEtl")),
                        ),
                        PoiDataEntry(
                            name = "Legacy Federal Campgrounds",
                            category = "campground",
                            etls = listOf(EtlEntry(slug = "legacy-federal-campgrounds", adapter = "LegacyFederalEtl")),
                        ),
                        PoiDataEntry(
                            name = "Explicitly Disabled Campgrounds",
                            enabled = false,
                            category = "campground",
                            etls = listOf(EtlEntry(slug = "campflare-campgrounds", adapter = "CampflareCampgroundsEtl")),
                        ),
                    ),
                campsiteData =
                    listOf(
                        CampsiteDataEntry(
                            name = "Runnable Campsites",
                            etls = listOf(EtlEntry(slug = "campflare-campsites", adapter = "CampflareCampsitesEtl")),
                        ),
                        CampsiteDataEntry(
                            name = "Legacy Federal Campsites",
                            etls = listOf(EtlEntry(slug = "legacy-federal-campsites", adapter = "LegacyFederalSitesEtl")),
                        ),
                    ),
                poiReservableJoiners =
                    listOf(
                        PoiReservableJoinerEntry(
                            name = "Federal Campsites to Federal Campgrounds",
                            adapter = "LegacyFederalJoiner",
                        ),
                    ),
            )

        assertEquals(
            listOf("Runnable Campgrounds", "Runnable Campsites"),
            importTargetsFromRegistry(registry).keys.toList(),
        )
    }

    @Test
    fun `production import fan-out includes every configured canonical catalog source`() {
        val registry =
            PoiRegistry.load(
                File(System.getProperty("user.dir"))
                    .resolve("../config/poi-registry.yaml")
                    .canonicalFile,
            )

        assertEquals(
            listOf(
                "Campflare Campgrounds",
                "Federal Campgrounds",
                "Washington State Parks",
                "BC Provincial Parks",
                "Parks Canada",
                "Alberta Provincial Parks",
                "New York State Parks",
                "California State Parks",
                "Planet Fitness",
                "Tesla Superchargers",
                "Campflare Campsites",
                "Federal Campsites",
                "Washington Aspira Resources",
                "BC Aspira Resources",
                "Parks Canada Aspira Resources",
                "California State Park Sites",
                "Alberta Provincial Park Sites",
                "New York State Park Sites",
                "Federal Campsites → Federal Campgrounds",
                "Aspira Resources → Aspira Pins",
                "ReserveCalifornia Sites → California State Parks",
                "ReserveAmerica Sites → Alberta + NY Parks",
            ),
            importTargetsFromRegistry(registry).keys.toList(),
        )
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
