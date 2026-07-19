package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.registry.CampsiteDataEntry
import ca.floo.roadtrip.model.metadata.registry.EtlEntry
import ca.floo.roadtrip.model.metadata.registry.PoiDataEntry
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistryTargetsTest {
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
                            name = "Legacy Rec.gov Campgrounds",
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
                            name = "Legacy Rec.gov Campsites",
                            etls = listOf(EtlEntry(slug = "legacy-federal-campsites", adapter = "LegacyFederalSitesEtl")),
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
        val registry = PoiRegistry.loadResource("poi-registry.yaml")

        assertEquals(
            listOf(
                "Campflare Campgrounds",
                "Rec.gov Campgrounds",
                "Washington State Parks",
                "BC Provincial Parks",
                "Parks Canada",
                "Alberta Provincial Parks",
                "New York State Parks",
                "California State Parks",
                "Planet Fitness",
                "Tesla Superchargers",
                "Campflare Campsites",
                "Rec.gov Campsites",
                "Washington Aspira Resources",
                "BC Aspira Resources",
                "Parks Canada Aspira Resources",
                "California State Park Sites",
                "Alberta Provincial Park Sites",
                "New York State Park Sites",
            ),
            importTargetsFromRegistry(registry).keys.toList(),
        )
    }
}
