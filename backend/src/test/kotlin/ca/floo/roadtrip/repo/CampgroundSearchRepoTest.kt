package ca.floo.roadtrip.repo

import ca.floo.roadtrip.fixtures.CatalogPoiFixture
import ca.floo.roadtrip.model.domain.CampgroundSearchFilter
import ca.floo.roadtrip.model.domain.CampsiteKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A square around Lake Tahoe; every seed below sits inside unless the test says otherwise. */
private const val TAHOE =
    """{"type":"Polygon","coordinates":[[[-120.4,38.7],[-119.6,38.7],[-119.6,39.4],[-120.4,39.4],[-120.4,38.7]]]}"""

private val noFilter = CampgroundSearchFilter(siteType = null, groupSize = null, amenities = emptyList())

class CampgroundSearchRepoTest : SharedDbTest() {
    private val campsites by lazy { CampsiteRepo(ctx) }

    private fun repo() = CampgroundSearchRepo(ctx, enabledDataProviders = setOf("recgov", "campflare"))

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seed(
        sourceId: String,
        lon: Double,
        lat: Double,
        source: String = "recgov",
        amenitiesJson: String = "[]",
    ): CatalogPoiFixture =
        ctx.seedCatalogPoi(
            sourceId = sourceId,
            name = sourceId,
            lon = lon,
            lat = lat,
            source = source,
            amenitiesJson = amenitiesJson,
        )

    private fun site(
        campgroundId: Long,
        vendorId: String,
        kind: CampsiteKind,
        maxPeople: Int? = null,
    ) {
        ctx.seedCampsite(campgroundId = campgroundId, vendorId = vendorId, kind = kind.wire, maxPeople = maxPeople)
        campsites.refreshSiteSummaries(listOf(campgroundId))
    }

    @Test
    fun `returns the campgrounds inside the boundary nearest the centre first`() {
        val near = seed("near", -120.0, 39.05)
        val far = seed("far", -120.35, 38.75)
        seed("outside", -118.0, 37.0)

        val result = repo().searchWithinBoundary(TAHOE, noFilter, limit = 10)

        assertEquals(listOf(near.poiId, far.poiId), result.poiIds)
        assertEquals(2, result.totalInBoundary)
        assertFalse(result.truncated)
    }

    @Test
    fun `a site type keeps campgrounds with that kind or with no sites at all`() {
        val tent = seed("tent", -120.0, 39.0)
        site(tent.campgroundId, "1", CampsiteKind.TENT)
        val rvOnly = seed("rv", -120.1, 39.0)
        site(rvOnly.campgroundId, "2", CampsiteKind.RV)
        val unknown = seed("unknown", -120.2, 39.0)

        val result = repo().searchWithinBoundary(TAHOE, noFilter.copy(siteType = "tent"), limit = 10)

        assertEquals(setOf(tent.poiId, unknown.poiId), result.poiIds.toSet())
        assertEquals(3, result.totalInBoundary)
    }

    @Test
    fun `a group size drops only campgrounds whose known cap is below it`() {
        val big = seed("big", -120.0, 39.0)
        site(big.campgroundId, "1", CampsiteKind.TENT, maxPeople = 8)
        val small = seed("small", -120.1, 39.0)
        site(small.campgroundId, "2", CampsiteKind.TENT, maxPeople = 2)
        val unknown = seed("unknown", -120.2, 39.0)
        site(unknown.campgroundId, "3", CampsiteKind.TENT)

        val result = repo().searchWithinBoundary(TAHOE, noFilter.copy(groupSize = 4), limit = 10)

        assertEquals(setOf(big.poiId, unknown.poiId), result.poiIds.toSet())
    }

    @Test
    fun `an amenity drops only campgrounds that state it is absent`() {
        val has = seed("has", -120.0, 39.0, amenitiesJson = """[{"key":"toilets","present":true}]""")
        val lacks = seed("lacks", -120.1, 39.0, amenitiesJson = """[{"key":"toilets","present":false}]""")
        val silent = seed("silent", -120.2, 39.0, amenitiesJson = """[{"key":"showers","present":true}]""")

        val result = repo().searchWithinBoundary(TAHOE, noFilter.copy(amenities = listOf("toilets")), limit = 10)

        assertEquals(setOf(has.poiId, silent.poiId), result.poiIds.toSet())
        assertTrue(lacks.poiId !in result.poiIds)
    }

    @Test
    fun `the limit truncates and says so`() {
        seed("a", -120.0, 39.0)
        seed("b", -120.1, 39.0)
        seed("c", -120.2, 39.0)

        val result = repo().searchWithinBoundary(TAHOE, noFilter, limit = 2)

        assertEquals(2, result.poiIds.size)
        assertEquals(3, result.totalInBoundary)
        assertTrue(result.truncated)
    }

    @Test
    fun `campgrounds from a disabled data provider are not served`() {
        seed("hidden", -120.0, 39.0, source = "reserveamerica")
        val shown = seed("shown", -120.1, 39.0)

        val result = repo().searchWithinBoundary(TAHOE, noFilter, limit = 10)

        assertEquals(listOf(shown.poiId), result.poiIds)
    }
}
