package ca.floo.roadtrip.service.poi

import ca.floo.roadtrip.model.api.poi.PoiCategoryDetailSchema
import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.domain.poi.CampgroundPoiDetail
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCatalogPoi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PoiServiceTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `detail row surfaces primary campground vendor ref`() {
        val poiId =
            seedPoi(
                providerRefJson = """{"transactionLocationId":-2147483647,"mapId":-2147483026,"resourceLocationId":-2147483640}""",
                propertiesJson = """{"upstream":{"booking_cta_provider_ref":null}}""",
            )

        val feature = poiService().poiDetail(poiId)
        val row = campgroundDetailRow(poiId)

        assertNotNull(feature)
        val publicRef = feature.campgroundDetail().providerRef!!.jsonObject
        assertEquals("-2147483026", publicRef["mapId"]!!.jsonPrimitive.content)
        assertEquals("-2147483647", publicRef["transactionLocationId"]!!.jsonPrimitive.content)
        assertEquals("-2147483640", publicRef["resourceLocationId"]!!.jsonPrimitive.content)
        assertNull(row.ctaProviderRefJson)
    }

    @Test
    fun `detail row ignores old materialized Aspira CTA ref from source payload`() {
        val poiId =
            seedPoi(
                providerRefJson = """{"transactionLocationId":-2147483647,"mapId":-2147483026,"resourceLocationId":-2147483640}""",
                propertiesJson =
                    """
                    {"upstream":{"booking_cta_provider_ref":{
                      "transactionLocationId":-2147483647,
                      "mapId":-2147483645,
                      "resourceLocationId":-2147483640
                    }}}
                    """.trimIndent(),
            )

        val row = campgroundDetailRow(poiId)

        assertNull(row.ctaProviderRefJson)
    }

    @Test
    fun `detail row projects first campground link as info URL`() {
        val link = "https://www.fs.usda.gov/recarea/tahoe/recarea/?recid=80728"
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "lake-of-the-woods-campground-192",
                name = "Lake Of The Woods Campground",
                lon = -120.391227722,
                lat = 39.503097534,
                source = SOURCE,
                subcategory = null,
                agency = "USDA Forest Service",
                region = null,
                country = null,
                providerRefJson = """{"campflare_id":"lake-of-the-woods-campground-192"}""",
            )
        ctx.execute(
            "UPDATE campgrounds SET links = ?::jsonb WHERE id = ?",
            """[{"url":"$link","title":"Lake of the Woods"}]""",
            fixture.catalogId,
        )

        val feature = poiService().poiDetail(fixture.poiId)

        assertNotNull(feature)
        assertEquals(link, feature.campgroundDetail().infoUrl)
    }

    @Test
    fun `detail row exposes sources and vendor refs via canonical view`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "recgov-232869",
                name = "Cold Creek",
                lon = -120.3147222,
                lat = 39.5427778,
                source = "recgov",
                subcategory = "established",
                agency = "USDA Forest Service",
                region = "CA",
                country = "US",
                providerRefJson = """{"recgov_id":"232869"}""",
                bookingProvider = "recgov",
                bookingProviderRef = "232869",
            )
        ctx.execute(
            "UPDATE campgrounds SET reservation_url = ? WHERE id = ?",
            "https://www.recreation.gov/camping/campgrounds/232869",
            fixture.catalogId,
        )

        val feature = poiService().poiDetail(fixture.poiId)

        assertNotNull(feature)
        assertEquals("recgov", feature.properties.source)
        assertEquals("recgov-232869", feature.properties.sourceId)
        val detail = feature.campgroundDetail()
        assertEquals("https://www.recreation.gov/camping/campgrounds/232869", detail.reserveUrl)
        val publicRef = detail.providerRef!!.jsonObject
        assertEquals("232869", publicRef["recgov_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("recgov"), detail.sources)
    }

    @Test
    fun `detail provider ref follows linked campground provider candidate ordering`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "upper-pines-campflare",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "campflare",
                providerRefJson = """{"campflare_id":"upper-pines-campground-447"}""",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-campground-447",
            )

        val defaultRef =
            Json
                .parseToJsonElement(campgroundDetailRow(fixture.poiId).providerRefJson!!)
                .jsonObject
        assertEquals("upper-pines-campground-447", defaultRef["campflare_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `low zoom default poi request suppresses campgrounds`() {
        ctx.seedCatalogPoi(sourceId = "cg-1", name = "Camp", lon = -123.0, lat = 49.0, poiType = "campground")
        ctx.seedCatalogPoi(sourceId = "tesla-1", name = "Tesla", lon = -123.05, lat = 49.05, poiType = "tesla_supercharger")
        ctx.seedCatalogPoi(
            sourceId = "pf-1",
            name = "Planet Fitness",
            lon = -123.1,
            lat = 49.1,
            poiType = "planet_fitness_location",
        )

        val categories =
            poiService()
                .pois(
                    bbox = vancouverBbox,
                    zoom = CampgroundService.MIN_POI_ZOOM - 1,
                    categories = null,
                ).features
                .map { it.properties.category }
                .toSet()

        assertEquals(setOf("tesla_supercharger", "planet_fitness_location"), categories)
    }

    @Test
    fun `low zoom campground-only request returns no pois`() {
        ctx.seedCatalogPoi(sourceId = "cg-1", name = "Camp", lon = -123.0, lat = 49.0, poiType = "campground")
        ctx.seedCatalogPoi(sourceId = "tesla-1", name = "Tesla", lon = -123.05, lat = 49.05, poiType = "tesla_supercharger")

        val features =
            poiService()
                .pois(
                    bbox = vancouverBbox,
                    zoom = CampgroundService.MIN_POI_ZOOM - 1,
                    categories = listOf("campground"),
                ).features

        assertEquals(emptyList(), features)
    }

    private fun seedPoi(
        providerRefJson: String,
        propertiesJson: String = "{}",
    ): Long =
        ctx
            .seedCatalogPoi(
                sourceId = "lake-louise",
                name = "Lake Louise Campground",
                lon = -116.18,
                lat = 51.42,
                source = SOURCE,
                subcategory = "federal",
                agency = "Parks Canada",
                region = "AB",
                country = "CA",
                providerRefJson = providerRefJson,
                propertiesJson = propertiesJson,
                bookingProvider = SOURCE,
                bookingProviderRef = "lake-louise",
            ).poiId

    private fun poiService(): PoiService =
        PoiService(
            poiRepo = PoiServingRepo(ctx, enabledDataProviders = setOf(SOURCE, "campflare", "recgov", "test")),
            detailServices =
                listOf(
                    CampgroundService(
                        campgroundRepo = CampgroundRepo(ctx),
                        dateResolver =
                            ca.floo.roadtrip.service.availability
                                .AvailabilityDateResolver(ctx),
                    ),
                    TeslaSuperchargerService(TeslaSuperchargerRepo(ctx)),
                    PlanetFitnessLocationService(PlanetFitnessLocationRepo(ctx)),
                ),
        )

    private fun campgroundDetailRow(poiId: Long): CampgroundPoiDetail = CampgroundRepo(ctx).findPoiDetailByPoi(poiId)!!

    private fun PoiDetailFeatureSchema.campgroundDetail(): PoiCategoryDetailSchema = properties.detail

    private companion object {
        const val SOURCE = "poi-serving-test"
        val vancouverBbox = Bbox(west = -125.0, south = 47.0, east = -120.0, north = 51.0)
    }
}
