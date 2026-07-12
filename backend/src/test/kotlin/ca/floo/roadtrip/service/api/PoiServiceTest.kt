package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.domain.Bbox
import ca.floo.roadtrip.models.domain.PoiDetailRow
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.catalog.CampgroundService
import ca.floo.roadtrip.service.catalog.PlanetFitnessLocationService
import ca.floo.roadtrip.service.catalog.TeslaSuperchargerService
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
        CanonicalViewRepo(ctx).refreshCanonicalViews()

        val feature = poiService().poiDetail(poiId)
        val row = campgroundDetailRow(poiId)

        assertNotNull(feature)
        val publicRef = feature.properties.providerRef!!.jsonObject
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
        CanonicalViewRepo(ctx).refreshCanonicalViews()

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
        CanonicalViewRepo(ctx).refreshCanonicalViews()

        val feature = poiService().poiDetail(fixture.poiId)

        assertNotNull(feature)
        assertEquals(link, feature.properties.infoUrl)
    }

    @Test
    fun `detail row exposes sources and vendor refs via canonical view`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "recgov-232869",
                name = "Cold Creek",
                lon = -120.3147222,
                lat = 39.5427778,
                source = "federal-campgrounds",
                subcategory = "established",
                agency = "USDA Forest Service",
                region = "CA",
                country = "US",
                providerRefJson = """{"catalog_id":"recgov-232869"}""",
            )
        ctx.execute(
            "UPDATE campgrounds SET reservation_url = ? WHERE id = ?",
            "https://www.recreation.gov/camping/campgrounds/232869",
            fixture.catalogId,
        )
        val recgovVendorRefId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO vendor_refs (
                      vendor, entity_type, external_id, external_name, payload
                    ) VALUES (
                      'recgov', 'campground', '232869', 'Cold Creek', '{"recgov_id":"232869"}'::jsonb
                    )
                    RETURNING id
                    """.trimIndent(),
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            "INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id) VALUES (?, ?)",
            fixture.catalogId,
            recgovVendorRefId,
        )
        CanonicalViewRepo(ctx).refreshCanonicalViews()

        val feature = poiService().poiDetail(fixture.poiId)

        assertNotNull(feature)
        assertEquals("federal-campgrounds", feature.properties.source)
        assertEquals("recgov-232869", feature.properties.sourceId)
        assertEquals("https://www.recreation.gov/camping/campgrounds/232869", feature.properties.reserveUrl)
        val publicRef = feature.properties.providerRef!!.jsonObject
        assertEquals("232869", publicRef["recgov_id"]!!.jsonPrimitive.content)
        // Ungrouped seed row (match_group_id NULL) is its own group; canonical
        // view returns a single-element member_sources equal to data_source.
        assertEquals(listOf("federal-campgrounds"), feature.properties.sources)
    }

    @Test
    fun `detail provider ref follows campground provider candidate ordering`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "upper-pines-campflare",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "campflare",
                providerRefJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        val siblingCampgroundId =
            ctx.seedCampground(
                name = "Upper Pines",
                source = "recgov",
                sourceId = "recgov-232447",
                providerRefJson = """{"recgov_id":"232447"}""",
            )
        matchAndGroupCampgrounds(fixture.catalogId, siblingCampgroundId)
        CanonicalViewRepo(ctx).refreshCanonicalViews()

        val defaultRef =
            Json
                .parseToJsonElement(campgroundDetailRow(fixture.poiId).providerRefJson!!)
                .jsonObject
        assertEquals("upper-pines-campground-447", defaultRef["campflare_id"]!!.jsonPrimitive.content)

        ctx.execute(
            "UPDATE campgrounds SET preferred_availability_source = ? WHERE id = ?",
            "recgov",
            fixture.catalogId,
        )
        CanonicalViewRepo(ctx).refreshCanonicalViews()

        val preferredRef =
            Json
                .parseToJsonElement(campgroundDetailRow(fixture.poiId).providerRefJson!!)
                .jsonObject
        assertEquals("232447", preferredRef["recgov_id"]!!.jsonPrimitive.content)
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
                    bbox = VANCOUVER_BBOX,
                    zoom = POI_CAMPGROUND_MIN_ZOOM - 1,
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
                    bbox = VANCOUVER_BBOX,
                    zoom = POI_CAMPGROUND_MIN_ZOOM - 1,
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
            ).poiId

    private fun poiService(): PoiService =
        PoiService(
            poiRepo = PoiServingRepo(ctx),
            campgroundService = CampgroundService(CampgroundRepo(ctx)),
            teslaSuperchargerService = TeslaSuperchargerService(TeslaSuperchargerRepo(ctx)),
            planetFitnessLocationService = PlanetFitnessLocationService(PlanetFitnessLocationRepo(ctx)),
        )

    private fun campgroundDetailRow(poiId: Long): PoiDetailRow =
        CampgroundService(CampgroundRepo(ctx)).poiDetail(PoiServingRepo(ctx).findById(poiId)!!)!!

    private fun matchAndGroupCampgrounds(
        aId: Long,
        bId: Long,
    ) {
        val lo = minOf(aId, bId)
        val hi = maxOf(aId, bId)
        ctx.execute(
            """
            INSERT INTO campground_matches (campground_a_id, campground_b_id, heuristic)
            VALUES (?, ?, '{"method":"manual","score":1.0}'::jsonb)
            """.trimIndent(),
            lo,
            hi,
        )
        ctx.execute("UPDATE campgrounds SET match_group_id = ? WHERE id IN (?, ?)", lo, lo, hi)
    }

    private companion object {
        const val SOURCE = "poi-serving-test"
        val VANCOUVER_BBOX = Bbox(west = -125.0, south = 47.0, east = -120.0, north = 51.0)
    }
}
