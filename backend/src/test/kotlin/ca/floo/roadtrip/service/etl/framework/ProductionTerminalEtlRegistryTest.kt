package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.ADAPTER_POLICIES
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampgroundsEtl
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

    /**
     * The validator's table and the factory are two halves of one fact. A policy
     * keyed on an adapter no factory builds judges nothing and drifts unnoticed.
     */
    @Test
    fun `every adapter policy names an adapter some factory builds`() {
        val known = poiAdapters.keys + campsiteAdapters.keys
        val orphaned = ADAPTER_POLICIES.keys - known
        assertTrue(orphaned.isEmpty(), "ADAPTER_POLICIES names adapters no factory builds: $orphaned")
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
            val geometry = checkNotNull(entry.geometry) { entry.slug }
            val definition = poiAdapters["AspiraCampgroundsEtl"]?.create?.invoke(entry)
            assertNotNull(definition, entry.slug)

            // The policy has to reach the ETL, not merely sit on the entry: the
            // sources it builds are the ones the row declared, in that order.
            val etl = definition.etl as AspiraCampgroundsEtl
            val bundle = InputBundle(LinkedHashMap(entry.inputs.associateWith { emptyList<Envelope>() }))
            assertEquals(geometry.sources.map { it.input }, etl.geometrySourcesFor(bundle).map { it.first }, entry.slug)
        }
    }
}
