package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampsiteKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampgroundRepoSummaryReadTest : SharedDbTest() {
    private val repo by lazy { CampgroundRepo(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `reads one row per known id in request order with its summary`() {
        val a = ctx.seedCatalogPoi(sourceId = "a", name = "Alpha", lon = -120.0, lat = 39.0, agency = "USFS")
        val b = ctx.seedCatalogPoi(sourceId = "b", name = "Beta", lon = -120.1, lat = 39.1)
        ctx.seedCampsite(campgroundId = a.campgroundId, vendorId = "1", kind = CampsiteKind.TENT.wire, maxPeople = 6)

        val rows = repo.findSummariesByPoiIds(listOf(b.poiId, a.poiId, 999_999L))

        assertEquals(listOf(b.poiId, a.poiId), rows.map { it.poiId })
        val alpha = rows.last()
        assertEquals("Alpha", alpha.campground.name)
        assertEquals("USFS", alpha.campground.management?.agency)
        assertEquals(-120.0, alpha.lng, 1e-6)
        assertEquals(39.0, alpha.lat, 1e-6)
        assertEquals(1, alpha.summary?.siteTotal)
        assertEquals(mapOf(CampsiteKind.TENT to 1), alpha.summary?.siteCounts)
        assertEquals(6, alpha.summary?.maxPeople)
        assertNull(rows.first().summary)
    }

    @Test
    fun `an empty request reads nothing`() {
        assertEquals(emptyList(), repo.findSummariesByPoiIds(emptyList()))
    }
}
