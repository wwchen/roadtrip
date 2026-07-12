package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.models.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.models.domain.CatalogVendorRefUpsertCandidate
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CampsiteParentJoinerRepoTest : SharedDbTest() {
    @BeforeEach
    fun resetCatalog() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `recgov joiner only reparents recgov-owned campsite rows to federal-owned campground rows`() {
        val campgrounds = CampgroundRepo(ctx)
        val campsites = CampsiteRepo(ctx)
        val joiners = CampsiteParentJoinerRepo(ctx)

        campgrounds.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    vendor = "campflare",
                    vendorRefId = CAMPFLARE_CAMPGROUND_ID,
                    name = "Upper Pines Campflare",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"id":"$CAMPFLARE_CAMPGROUND_ID"}"""),
                    vendorRefPayload = json("""{"campflare_id":"$CAMPFLARE_CAMPGROUND_ID"}"""),
                    additionalVendorRefs =
                        listOf(
                            CatalogVendorRefUpsertCandidate(
                                vendor = FEDERAL_CAMPGROUND_VENDOR,
                                vendorRefId = RECGOV_CAMPGROUND_ID,
                                payload = json("""{"recgov_id":"232447"}"""),
                            ),
                        ),
                ),
            ),
            source = "campflare-campgrounds",
        )
        campgrounds.upsertCampgrounds(
            listOf(
                CampgroundUpsertCandidate(
                    vendor = FEDERAL_CAMPGROUND_VENDOR,
                    vendorRefId = RECGOV_CAMPGROUND_ID,
                    name = "Upper Pines Federal",
                    latitude = 37.739,
                    longitude = -119.565,
                    sourcePayload = json("""{"FacilityID":"232447"}"""),
                    vendorRefPayload = json("""{"recgov_id":"232447"}"""),
                ),
            ),
            source = FEDERAL_CAMPGROUND_VENDOR,
        )
        campsites.upsertCampsites(
            listOf(
                CampsiteUpsertCandidate(
                    vendor = "campflare",
                    vendorRefId = "campflare-site-001",
                    parentVendor = "campflare",
                    parentVendorRefId = CAMPFLARE_CAMPGROUND_ID,
                    name = "001",
                    kind = "standard",
                    sourcePayload = json("""{"id":"campflare-site-001","campground_id":"$CAMPFLARE_CAMPGROUND_ID"}"""),
                    vendorRefPayload =
                        json("""{"campflare_id":"campflare-site-001","campground_id":"$CAMPFLARE_CAMPGROUND_ID"}"""),
                    additionalVendorRefs =
                        listOf(
                            CatalogVendorRefUpsertCandidate(
                                vendor = "recgov",
                                vendorRefId = RECGOV_CAMPSITE_ID,
                                payload = json("""{"recgov_id":"$RECGOV_CAMPSITE_ID"}"""),
                            ),
                        ),
                ),
            ),
            source = "campflare-campsites",
        )
        campsites.upsertCampsites(
            listOf(
                CampsiteUpsertCandidate(
                    vendor = "recgov",
                    vendorRefId = RECGOV_CAMPSITE_ID,
                    parentVendor = FEDERAL_CAMPGROUND_VENDOR,
                    parentVendorRefId = RECGOV_CAMPGROUND_ID,
                    name = "001",
                    kind = "standard",
                    sourcePayload =
                        json("""{"site":"001","_parent_facility_id":"232447"}"""),
                    vendorRefPayload =
                        json("""{"recgov_id":"$RECGOV_CAMPSITE_ID","_parent_facility_id":"232447"}"""),
                ),
            ),
            source = "federal-campsites",
        )

        val campflareCampgroundId = campgroundId("campflare")
        val federalCampgroundId = campgroundId(FEDERAL_CAMPGROUND_VENDOR)
        val campflareCampsiteId = campsiteId("campflare")
        val recgovCampsiteId = campsiteId("recgov")

        val links = joiners.discoverRecgovLinks()

        assertEquals(listOf(recgovCampsiteId to federalCampgroundId), links.map { it.campsiteId to it.campgroundId })

        joiners.reparentCampsites(links)

        assertEquals(campflareCampgroundId, campsiteParent(campflareCampsiteId))
        assertEquals(federalCampgroundId, campsiteParent(recgovCampsiteId))
    }

    private fun campgroundId(dataSource: String): Long =
        ctx
            .fetchOne("SELECT id FROM campgrounds WHERE data_source = ?", dataSource)!!
            .get("id", Long::class.java)

    private fun campsiteId(dataSource: String): Long =
        ctx
            .fetchOne("SELECT id FROM campsites WHERE data_source = ?", dataSource)!!
            .get("id", Long::class.java)

    private fun campsiteParent(campsiteId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM campsites WHERE id = ?", campsiteId)!!
            .get("campground_id", Long::class.java)

    private fun json(value: String) = Json.parseToJsonElement(value)

    private companion object {
        private const val CAMPFLARE_CAMPGROUND_ID = "upper-pines-campground-447"
        private const val FEDERAL_CAMPGROUND_VENDOR = "federal-campgrounds"
        private const val RECGOV_CAMPGROUND_ID = "recgov-232447"
        private const val RECGOV_CAMPSITE_ID = "105"
    }
}
