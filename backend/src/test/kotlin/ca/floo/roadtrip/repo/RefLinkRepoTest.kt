package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private const val RECGOV_ALIAS = """[{"provider":"recgov","ref":"232447"}]"""
private const val RECGOV_SITE_ALIAS = """[{"provider":"recgov","ref":"330257"}]"""

/**
 * A Campflare-primary row carrying a rec.gov alias has to be reachable from
 * either identity: the poller, the watch resolver and the booking seam all
 * arrive holding one ref and must not care which one.
 */
class RefLinkRepoTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `campground lookup by booking ref matches the primary and every alias`() {
        val campgroundId = seedAliasedCampground()
        val repo = RefLinkRepo(ctx)

        assertEquals(
            listOf(campgroundId),
            repo.campgroundIdsByBookingRef(BookingProviderRef.RecGov(facilityId = "232447")),
        )
        assertEquals(
            listOf(campgroundId),
            repo.campgroundIdsByBookingRef(BookingProviderRef.Campflare(campgroundId = "upper-pines-campground-447")),
        )
        assertEquals(
            emptyList(),
            repo.campgroundIdsByBookingRef(BookingProviderRef.RecGov(facilityId = "999999")),
        )
    }

    @Test
    fun `campsite lookups by booking ref match the primary and every alias`() {
        val campgroundId = seedAliasedCampground()
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = campgroundId,
                vendor = "campflare",
                vendorId = "upper-pines-site-100",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-site-100",
                bookingAliasesJson = RECGOV_SITE_ALIAS,
            )
        val repo = RefLinkRepo(ctx)

        assertEquals(listOf(campsiteId), repo.campsiteIdsByBookingRef(BookingProviderRef.RecGov(facilityId = "330257")))
        assertEquals(
            listOf(campsiteId),
            repo.campsiteIdsByBookingRef(BookingProviderRef.Campflare(campgroundId = "upper-pines-site-100")),
        )
        assertEquals(
            listOf(campsiteId),
            repo.campsiteIdsByCampgroundBookingRef(BookingProviderRef.RecGov(facilityId = "232447")),
        )
    }

    @Test
    fun `ref lookups return the primary first and then every alias`() {
        val campgroundId = seedAliasedCampground()
        val campsiteId =
            ctx.seedCampsite(
                campgroundId = campgroundId,
                vendor = "campflare",
                vendorId = "upper-pines-site-100",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-site-100",
                bookingAliasesJson = RECGOV_SITE_ALIAS,
            )
        val repo = RefLinkRepo(ctx)

        assertEquals(
            listOf(
                BookingProviderRef.Campflare(campgroundId = "upper-pines-campground-447"),
                BookingProviderRef.RecGov(facilityId = "232447"),
            ),
            repo.bookingRefsForCampground(campgroundId),
        )
        assertEquals(
            listOf(
                BookingProviderRef.Campflare(campgroundId = "upper-pines-site-100"),
                BookingProviderRef.RecGov(facilityId = "330257"),
            ),
            repo.bookingRefsForCampsite(campsiteId),
        )
        assertEquals(
            listOf(
                BookingProviderRef.Campflare(campgroundId = "upper-pines-campground-447"),
                BookingProviderRef.RecGov(facilityId = "232447"),
            ),
            repo.parentCampgroundBookingRefsForCampsite(campsiteId),
        )
    }

    @Test
    fun `a row with no booking identity at all resolves to no refs`() {
        val campgroundId = ctx.seedCampground(source = "campflare", sourceId = "bare-448")

        assertEquals(emptyList(), RefLinkRepo(ctx).bookingRefsForCampground(campgroundId))
    }

    private fun seedAliasedCampground(): Long =
        ctx.seedCampground(
            source = "campflare",
            sourceId = "upper-pines-campground-447",
            bookingProvider = "campflare",
            bookingProviderRef = "upper-pines-campground-447",
            bookingAliasesJson = RECGOV_ALIAS,
        )
}
