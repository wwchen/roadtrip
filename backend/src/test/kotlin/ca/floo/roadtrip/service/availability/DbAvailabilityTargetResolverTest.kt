package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderCapabilities
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * DB-backed tests for [DbAvailabilityTargetResolver.resolve], which
 * picks the winning provider_ref among a reservable's linked POIs. Mirrors the
 * DB setup helpers in [ca.floo.roadtrip.service.scheduler.jobs.AvailabilityPollExecutorTest].
 */
class DbAvailabilityTargetResolverTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    /** Seeds a POI. When [campgroundId] is null, provider_ref is left NULL (no
     *  resolvable provider) — otherwise it resolves to ProviderRef.RecGov. */
    private fun seedPoi(campgroundId: String?): Long =
        ctx
            .seedCatalogPoi(
                sourceId = "poi-${campgroundId ?: "none"}-${System.nanoTime()}",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "test",
                providerRefJson = campgroundId?.let { """{"recgov_id": "$it"}""" },
            ).poiId

    /** Seeds one site under [poiId]'s campground. */
    private fun seedCampsite(
        siteId: String,
        poiId: Long,
    ): Long =
        ctx.seedCampsite(
            campgroundId = campgroundIdFor(poiId),
            vendor = "recgov",
            vendorId = siteId,
            name = "Site $siteId",
        )

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

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

    private open class NoopCampflareProvider : AvailabilityProvider {
        override val id: AvailabilityProviderId = AvailabilityProviderId.CAMPFLARE
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = false,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private class DecliningCampflareProvider : NoopCampflareProvider() {
        override fun canHandle(ref: ProviderRef): Boolean = false
    }

    private fun resolverFor(
        campsitesRepo: CampsiteRepo,
        providers: Map<String, AvailabilityProvider> =
            mapOf(
                "test" to NoopRecgovProvider(),
                "federal-campgrounds" to NoopRecgovProvider(),
                "campflare" to NoopCampflareProvider(),
            ),
    ): DbAvailabilityTargetResolver =
        DbAvailabilityTargetResolver(
            providerRefs = CampsiteProviderRepo(ctx),
            campsitesRepo = campsitesRepo,
            availabilityProviders = AvailabilityProviderRegistry(providers),
            dateResolver = AvailabilityDateResolver(),
        )

    @Test
    fun `resolve carries the parent poi id that supplied the provider ref`() =
        runBlocking {
            val poiA = seedPoi(campgroundId = null)
            val poiB = seedPoi(campgroundId = "232447")
            val campsitesRepo = CampsiteRepo(ctx)
            val campsiteId = seedCampsite("100", poiB)
            val reservable = campsitesRepo.findById(campsiteId)!!

            val resolver = resolverFor(campsitesRepo)
            val t = resolver.resolve(reservable)!!

            assertEquals(poiB, t.parentPoiId)
            assertEquals("232447", parentRefKey(t.parentRef))
        }

    @Test
    fun `resolve uses primary campflare refs for campflare catalog rows`() =
        runBlocking {
            val poi =
                ctx
                    .seedCatalogPoi(
                        sourceId = "upper-pines-campflare",
                        name = "Upper Pines",
                        lon = -119.56,
                        lat = 37.74,
                        source = "campflare",
                        providerRefJson = """{"campflare_id":"upper-pines-campground-447"}""",
                    ).poiId
            val campgroundId = campgroundIdFor(poi)
            linkCampgroundRef(
                campgroundId = campgroundId,
                vendor = "federal-campgrounds",
                externalId = "recgov-232447",
                payloadJson = """{"recgov_id":"232447"}""",
            )
            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campgroundId,
                    vendor = "campflare",
                    vendorId = "upper-pines-site-100",
                    name = "Campflare Site 100",
                    providerRefJson = """{"campflare_id":"upper-pines-site-100"}""",
                )
            linkCampsiteRef(
                campsiteId = campsiteId,
                vendor = "recgov",
                externalId = "100",
                payloadJson = """{"recgov_id":"100"}""",
            )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target = resolverFor(campsitesRepo).resolve(reservable)!!

            assertEquals("campflare", reservable.vendor)
            assertEquals("upper-pines-site-100", reservable.vendorId)
            assertEquals(poi, target.parentPoiId)
            assertEquals(AvailabilityProviderId.CAMPFLARE, target.provider.id)
            assertEquals("upper-pines-campground-447", parentRefKey(target.parentRef))
        }

    @Test
    fun `resolve falls back to recgov aliases when campflare provider declines the ref`() =
        runBlocking {
            val poi =
                ctx
                    .seedCatalogPoi(
                        sourceId = "upper-pines-campflare-fallback",
                        name = "Upper Pines",
                        lon = -119.56,
                        lat = 37.74,
                        source = "campflare",
                        providerRefJson = """{"campflare_id":"upper-pines-campground-447"}""",
                    ).poiId
            val campgroundId = campgroundIdFor(poi)
            linkCampgroundRef(
                campgroundId = campgroundId,
                vendor = "federal-campgrounds",
                externalId = "recgov-232447",
                payloadJson = """{"recgov_id":"232447"}""",
            )
            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campgroundId,
                    vendor = "campflare",
                    vendorId = "upper-pines-site-100",
                    name = "Campflare Site 100",
                    providerRefJson = """{"campflare_id":"upper-pines-site-100"}""",
                )
            linkCampsiteRef(
                campsiteId = campsiteId,
                vendor = "recgov",
                externalId = "100",
                payloadJson = """{"recgov_id":"100"}""",
            )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target =
                resolverFor(
                    campsitesRepo = campsitesRepo,
                    providers =
                        mapOf(
                            "campflare" to DecliningCampflareProvider(),
                            "federal-campgrounds" to NoopRecgovProvider(),
                        ),
                ).resolve(reservable)!!

            assertEquals("campflare", reservable.vendor)
            assertEquals("upper-pines-site-100", reservable.vendorId)
            assertEquals(AvailabilityProviderId.RECGOV, target.provider.id)
            assertEquals("232447", parentRefKey(target.parentRef))
            assertEquals(campsiteId, target.catalogRef.campsiteId)
            assertEquals("100", target.catalogRef.vendorId)
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
            INSERT INTO campground_vendor_refs (campground_id, vendor_ref_id, is_primary)
            VALUES (?, ?, false)
            """.trimIndent(),
            campgroundId,
            vendorRefId,
        )
    }

    private fun linkCampsiteRef(
        campsiteId: Long,
        vendor: String,
        externalId: String,
        payloadJson: String,
    ) {
        val vendorRefId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO vendor_refs (vendor, entity_type, external_id, payload)
                    VALUES (?, 'campsite', ?, ?::jsonb)
                    RETURNING id
                    """.trimIndent(),
                    vendor,
                    externalId,
                    payloadJson,
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            """
            INSERT INTO campsite_vendor_refs (campsite_id, vendor_ref_id, is_primary)
            VALUES (?, ?, false)
            """.trimIndent(),
            campsiteId,
            vendorRefId,
        )
    }
}
