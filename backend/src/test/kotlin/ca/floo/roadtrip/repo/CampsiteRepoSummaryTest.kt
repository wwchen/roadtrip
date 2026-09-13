package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampgroundSiteSummary
import ca.floo.roadtrip.model.domain.CampsiteKind
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CampsiteRepoSummaryTest : SharedDbTest() {
    private val repo by lazy { CampsiteRepo(ctx) }

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `refresh counts live sites by kind and keeps the largest people count`() {
        val campground = ctx.seedCampground(name = "Nevada Beach", sourceId = "nb")
        ctx.seedCampsite(campgroundId = campground, vendorId = "1", kind = CampsiteKind.TENT.wire, maxPeople = 6)
        ctx.seedCampsite(campgroundId = campground, vendorId = "2", kind = CampsiteKind.TENT.wire, maxPeople = 8)
        ctx.seedCampsite(campgroundId = campground, vendorId = "3", kind = CampsiteKind.RV.wire, maxPeople = null)

        repo.refreshSiteSummaries(listOf(campground))

        assertEquals(
            CampgroundSiteSummary(siteTotal = 3, siteCounts = mapOf("tent" to 2, "rv" to 1), maxPeople = 8),
            repo.findSiteSummary(campground),
        )
    }

    @Test
    fun `a campground whose sites carry no people count summarises to null`() {
        val campground = ctx.seedCampground(name = "Zephyr Cove", sourceId = "zc")
        ctx.seedCampsite(campgroundId = campground, vendorId = "1", kind = CampsiteKind.STANDARD.wire)

        repo.refreshSiteSummaries(listOf(campground))

        assertEquals(
            CampgroundSiteSummary(siteTotal = 1, siteCounts = mapOf("standard" to 1), maxPeople = null),
            repo.findSiteSummary(campground),
        )
    }

    @Test
    fun `a campground with no live sites has no summary row`() {
        val campground = ctx.seedCampground(name = "Empty", sourceId = "empty")

        repo.refreshSiteSummaries(listOf(campground))

        assertNull(repo.findSiteSummary(campground))
    }

    @Test
    fun `soft-deleted sites are not counted`() {
        val campground = ctx.seedCampground(name = "Bayview", sourceId = "bv")
        ctx.seedCampsite(campgroundId = campground, vendorId = "1", kind = CampsiteKind.TENT.wire)
        val gone = ctx.seedCampsite(campgroundId = campground, vendorId = "2", kind = CampsiteKind.TENT.wire)
        ctx.execute("UPDATE campsites SET deleted_at = now() WHERE id = ?", gone)

        repo.refreshSiteSummaries(listOf(campground))

        assertEquals(1, repo.findSiteSummary(campground)?.siteTotal)
    }

    @Test
    fun `a refresh with no ids is a no-op`() {
        repo.refreshSiteSummaries(emptyList())
    }
}
