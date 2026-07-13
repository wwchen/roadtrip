package ca.floo.roadtrip.service.readpath

import ca.floo.roadtrip.config.ReadPathProviderConfig
import ca.floo.roadtrip.models.domain.poi.Bbox
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
import ca.floo.roadtrip.service.poi.PoiDetailService
import ca.floo.roadtrip.service.poi.PoiService
import ca.floo.roadtrip.service.poi.TeslaSuperchargerService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadPathProviderPoiReaderTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `bbox request excludes disabled data sources`() {
        val recgov =
            ctx.seedCatalogPoi(
                sourceId = "recgov-cg",
                name = "Recgov Camp",
                lon = -123.0,
                lat = 49.0,
                source = "recgov",
            )
        ctx.seedCatalogPoi(
            sourceId = "campflare-cg",
            name = "Campflare Camp",
            lon = -123.05,
            lat = 49.05,
            source = "campflare",
        )
        val tesla =
            ctx.seedCatalogPoi(
                sourceId = "tesla-1",
                name = "Tesla",
                lon = -123.1,
                lat = 49.1,
                poiType = "tesla_supercharger",
            )

        val ids =
            poiReader(enabledDataSources = setOf("recgov", "tesla_supercharger"))
                .pois(
                    bbox = VANCOUVER_BBOX,
                    zoom = CampgroundService.MIN_POI_ZOOM,
                    categories = null,
                ).features
                .map { it.id }
                .toSet()

        assertEquals(setOf(recgov.poiId, tesla.poiId), ids)
    }

    @Test
    fun `search excludes disabled data sources`() {
        val recgov =
            ctx.seedCatalogPoi(
                sourceId = "recgov-upper-pines",
                name = "Upper Pines",
                lon = -123.0,
                lat = 49.0,
                source = "recgov",
            )
        ctx.seedCatalogPoi(
            sourceId = "campflare-upper-pines",
            name = "Upper Pines Campflare",
            lon = -123.05,
            lat = 49.05,
            source = "campflare",
        )

        val hits =
            poiReader(enabledDataSources = setOf("recgov"))
                .search(query = "Upper", categories = listOf("campground"), limit = 10)
                .results

        assertEquals(listOf(recgov.poiId), hits.map { it.id })
    }

    @Test
    fun `detail returns null for a disabled data source`() {
        val hidden =
            ctx.seedCatalogPoi(
                sourceId = "campflare-hidden",
                name = "Hidden Camp",
                lon = -123.0,
                lat = 49.0,
                source = "campflare",
            )

        assertNull(poiReader(enabledDataSources = setOf("recgov")).poiDetail(hidden.poiId))
    }

    private fun poiReader(enabledDataSources: Set<String>): ReadPathProviderPoiReader {
        val detailServices =
            listOf<PoiDetailService>(
                CampgroundService(CampgroundRepo(ctx)),
                TeslaSuperchargerService(TeslaSuperchargerRepo(ctx)),
                PlanetFitnessLocationService(PlanetFitnessLocationRepo(ctx)),
            )
        return ReadPathProviderPoiReader(
            delegate =
                PoiService(
                    poiRepo = PoiServingRepo(ctx),
                    detailServices = detailServices,
                ),
            detailServices = detailServices,
            providers =
                ReadPathProviderConfig(
                    enabledDataSources = enabledDataSources,
                    enabledAvailabilityProviders = ENABLED_AVAILABILITY_PROVIDERS,
                ),
        )
    }

    private companion object {
        val VANCOUVER_BBOX = Bbox(west = -125.0, south = 47.0, east = -120.0, north = 51.0)
        val ENABLED_AVAILABILITY_PROVIDERS =
            setOf(
                "aspira",
                "campflare",
                "recgov",
                "reserveamerica",
                "reservecalifornia",
            )
    }
}
