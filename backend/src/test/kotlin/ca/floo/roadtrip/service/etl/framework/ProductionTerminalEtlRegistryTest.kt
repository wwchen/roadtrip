package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionTerminalEtlRegistryTest {
    @Test
    fun `adapter names resolve to the data provider they populate`() {
        assertEquals(DataProvider.RECGOV, dataProviderForAdapter("RecGovCampgroundsEtl"))
        assertEquals(DataProvider.STRAPI, dataProviderForAdapter("BcParksCampgroundsEtl"))
        assertEquals(DataProvider.TESLA_SUPERCHARGER, dataProviderForAdapter("TeslaIndexEtl"))
        assertNull(dataProviderForAdapter("NoSuchEtl"))
    }

    @Test
    fun `every terminal adapter named in the registry yaml has a factory`() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val poi = registry.poiData.mapNotNull { it.etls.lastOrNull()?.adapter }.toSet()
        val campsite = registry.campsiteData.mapNotNull { it.etls.lastOrNull()?.adapter }.toSet()
        assertTrue((poi - poiAdapters.keys).isEmpty(), "missing poi adapters: ${poi - poiAdapters.keys}")
        assertTrue((campsite - campsiteAdapters.keys).isEmpty(), "missing campsite adapters: ${campsite - campsiteAdapters.keys}")
    }

    @Test
    fun `the shipped bc parks terminal etl builds from the registry's tenant arg and geometry policy`() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val entry =
            registry.poiData
                .flatMap { it.etls }
                .single { it.slug == "aspira-bc-campgrounds" }
        assertEquals("bc", entry.args["tenant"])
        assertEquals("bcparks-strapi", checkNotNull(entry.geometry).sources.single().input)

        val definition = poiAdapters["BcParksCampgroundsEtl"]?.create?.invoke(entry)
        assertNotNull(definition)
    }

    @Test
    fun `both shipped aspira campground terminals build from their geometry policies`() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val entries =
            registry.poiData
                .flatMap { it.etls }
                .filter { it.adapter == "AspiraCampgroundsEtl" }
        assertEquals(listOf("aspira-wa-campgrounds", "aspira-pc-campgrounds"), entries.map { it.slug })

        for (entry in entries) {
            assertNotNull(entry.geometry)
            assertNotNull(poiAdapters["AspiraCampgroundsEtl"]?.create?.invoke(entry), entry.slug)
        }
    }
}
