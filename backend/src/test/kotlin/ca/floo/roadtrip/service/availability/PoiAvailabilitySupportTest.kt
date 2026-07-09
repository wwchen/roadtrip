package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CanonicalViewRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderCapabilities
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class PoiAvailabilitySupportTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    @Test
    fun `supports availability through recgov alias when campflare provider declines the ref`() {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "upper-pines-campflare-support",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "campflare",
                providerRefJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        linkCampgroundRef(
            campgroundId = fixture.catalogId,
            vendor = "federal-campgrounds",
            externalId = "recgov-232447",
            payloadJson = """{"recgov_id":"232447"}""",
        )
        val row = PoiServingRepo(ctx).fetchPoiById(fixture.poiId)!!
        val support =
            PoiAvailabilitySupport(
                providerRefs = CampsiteProviderRepo(ctx),
                availabilityProviders =
                    AvailabilityProviderRegistry(
                        mapOf(
                            "campflare" to DecliningCampflareProvider(),
                            "federal-campgrounds" to NoopRecgovProvider(),
                        ),
                    ),
            )

        assertEquals(true, support.supports(row))
    }

    @Test
    fun `preferredAvailabilityProvider follows preferred_availability_source when set`() {
        val group = seedDualVendorGroupedPoi()
        val support = supportFor()

        // No preference set: match-group winner (campflare) is the preferred candidate.
        assertEquals("campflare", support.preferredAvailabilityProvider(group.poiId))

        // Flip preference to recgov on the canonical winner and refresh — the
        // resolver ordering now floats recgov to the top, so the API field
        // flips with it.
        ctx.execute(
            "UPDATE campgrounds SET preferred_availability_source = ? WHERE id = ?",
            "recgov",
            group.winnerCampgroundId,
        )
        CanonicalViewRepo(ctx).refreshCanonicalViews()

        assertEquals("recgov", support.preferredAvailabilityProvider(group.poiId))
    }

    @Test
    fun `preferredAvailabilityProvider falls back to winner source when no preference is set`() {
        val group = seedDualVendorGroupedPoi()
        val support = supportFor()

        assertEquals("campflare", support.preferredAvailabilityProvider(group.poiId))
    }

    /** POI-independent support instance; the preferredAvailabilityProvider path
     *  only consults [CampsiteProviderRepo] and doesn't dispatch to adapters. */
    private fun supportFor(): PoiAvailabilitySupport =
        PoiAvailabilitySupport(
            providerRefs = CampsiteProviderRepo(ctx),
            availabilityProviders = AvailabilityProviderRegistry(emptyMap()),
        )

    private data class GroupedPoi(
        val poiId: Long,
        val winnerCampgroundId: Long,
    )

    /** Winner campflare POI + recgov sibling grouped under a shared match_group_id.
     *  Mirrors the fixture in DbAvailabilityTargetResolverTest so both tests
     *  exercise the same ordering surface. */
    private fun seedDualVendorGroupedPoi(): GroupedPoi {
        val fixture =
            ctx.seedCatalogPoi(
                sourceId = "upper-pines-preferred-provider",
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
        val lo = minOf(fixture.catalogId, siblingCampgroundId)
        val hi = maxOf(fixture.catalogId, siblingCampgroundId)
        ctx.execute(
            """
            INSERT INTO campground_matches (campground_a_id, campground_b_id, heuristic)
            VALUES (?, ?, '{"method":"manual","score":1.0}'::jsonb)
            """.trimIndent(),
            lo,
            hi,
        )
        ctx.execute("UPDATE campgrounds SET match_group_id = ? WHERE id IN (?, ?)", lo, lo, hi)
        CanonicalViewRepo(ctx).refreshCanonicalViews()
        // Canonical winner = richest, tie-break lowest id. Both campgrounds are
        // equally sparse here, so the seed-first (campflare, lower id) row wins.
        return GroupedPoi(poiId = fixture.poiId, winnerCampgroundId = fixture.catalogId)
    }

    private fun linkCampgroundRef(
        campgroundId: Long,
        vendor: String,
        externalId: String,
        payloadJson: String,
    ) {
        val vendorRefId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO vendor_refs (vendor, entity_type, external_id, payload)
                    VALUES (?, 'campground', ?, ?::jsonb)
                    RETURNING id
                    """.trimIndent(),
                    vendor,
                    externalId,
                    payloadJson,
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            """
            INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id)
            VALUES (?, ?)
            """.trimIndent(),
            campgroundId,
            vendorRefId,
        )
    }

    private class NoopRecgovProvider : AvailabilityProvider {
        override val id: AvailabilityProviderId = AvailabilityProviderId.RECGOV
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private class DecliningCampflareProvider : AvailabilityProvider {
        override val id: AvailabilityProviderId = AvailabilityProviderId.CAMPFLARE
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = false,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun canHandle(ref: ProviderRef): Boolean = false

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
