package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.CampsiteParentLink
import ca.floo.roadtrip.service.etl.framework.JoinerCtx
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraCampsiteParentJoiner
import ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaCampsiteParentJoiner
import ca.floo.roadtrip.service.etl.vendors.reservecalifornia.ReserveCaliforniaCampsiteParentJoiner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CampsiteParentJoinerRepoTest : SharedDbTest() {
    @BeforeEach
    fun reset() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `reserve california links sites to parent campground by external place ref`() {
        val oldCampgroundId =
            ctx.seedCampground(
                name = "Old California Parent",
                source = "reservecalifornia",
                sourceId = "old-parent",
                refresh = false,
            )
        val targetCampgroundId =
            ctx.seedCampground(
                name = "California State Park",
                source = "reservecalifornia",
                sourceId = "rc-690",
                providerRefJson = """{"place_id":690,"facility_ids":[612]}""",
                refresh = false,
            )
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = oldCampgroundId,
                vendor = "reservecalifornia",
                vendorId = "12345",
                providerRefJson = """{"unit_id":12345,"facility_id":612,"place_id":690,"_parent_place_id":690}""",
                refresh = false,
            )

        val links = ReserveCaliforniaCampsiteParentJoiner().discoverLinks(joinerCtx())

        assertEquals(
            setOf(CampsiteParentLink(campsiteId = campsiteId, campgroundId = targetCampgroundId)),
            links.toSet(),
        )
    }

    @Test
    fun `aspira links sites to parent campground by map external ref`() {
        val oldCampgroundId =
            ctx.seedCampground(
                name = "Old Aspira Parent",
                source = "aspira",
                sourceId = "old-parent",
                refresh = false,
            )
        val targetCampgroundId =
            ctx.seedCampground(
                name = "BC Parks Pin",
                source = "aspira",
                sourceId = "aspira-4189--2147483361",
                providerRefJson =
                    """{"transactionLocationId":4189,"mapId":-2147483361,"resourceLocationId":-2147483408}""",
                refresh = false,
            )
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = oldCampgroundId,
                vendor = "aspira",
                vendorId = "-2147483408",
                providerRefJson =
                    """{"transactionLocationId":4189,"mapId":-2147483361,"resourceLocationId":-2147483408}""",
                refresh = false,
            )

        val links = AspiraCampsiteParentJoiner().discoverLinks(joinerCtx())

        assertEquals(
            setOf(CampsiteParentLink(campsiteId = campsiteId, campgroundId = targetCampgroundId)),
            links.toSet(),
        )
    }

    @Test
    fun `aspira falls back to resource location when map parent ref does not match`() {
        val oldCampgroundId =
            ctx.seedCampground(
                name = "Old Aspira Parent",
                source = "aspira",
                sourceId = "old-parent",
                refresh = false,
            )
        val targetCampgroundId =
            ctx.seedCampground(
                name = "BC Parks Parent Pin",
                source = "aspira",
                sourceId = "aspira-4189-parent-map",
                providerRefJson =
                    """{"transactionLocationId":4189,"mapId":"parent-map","resourceLocationId":-2147483408}""",
                refresh = false,
            )
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = oldCampgroundId,
                vendor = "aspira",
                vendorId = "-2147483408",
                providerRefJson =
                    """{"transactionLocationId":4189,"mapId":-2147483361,"resourceLocationId":-2147483408}""",
                refresh = false,
            )

        val links = AspiraCampsiteParentJoiner().discoverLinks(joinerCtx())

        assertEquals(
            setOf(CampsiteParentLink(campsiteId = campsiteId, campgroundId = targetCampgroundId)),
            links.toSet(),
        )
    }

    @Test
    fun `reserve america parent links are scoped by contract when park ids collide`() {
        val oldCampgroundId =
            ctx.seedCampground(
                name = "Old ReserveAmerica Parent",
                source = "reserveamerica",
                sourceId = "old-parent",
                refresh = false,
            )
        val albertaCampgroundId =
            ctx.seedCampground(
                name = "Alberta Park",
                source = "reserveamerica",
                sourceId = "ra-330800",
                providerRefJson = """{"contract_code":"ABPP","park_id":"330800"}""",
                refresh = false,
            )
        ctx.seedCampground(
            name = "New York Park With Same Park Id",
            source = "reserveamerica",
            sourceId = "ra-330800",
            providerRefJson = """{"contract_code":"NY","park_id":"330800"}""",
            refresh = false,
        )
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = oldCampgroundId,
                vendor = "reserveamerica",
                vendorId = "15301",
                providerRefJson =
                    """{"site_id":"15301","_parent_contract_code":"ABPP","_parent_park_id":"330800"}""",
                refresh = false,
            )

        val links = ReserveAmericaCampsiteParentJoiner().discoverLinks(joinerCtx())

        assertEquals(
            setOf(CampsiteParentLink(campsiteId = campsiteId, campgroundId = albertaCampgroundId)),
            links.toSet(),
        )
    }

    private fun joinerCtx(): JoinerCtx = JoinerCtx(campsiteParentJoinerRepo = CampsiteParentJoinerRepo(ctx))
}
