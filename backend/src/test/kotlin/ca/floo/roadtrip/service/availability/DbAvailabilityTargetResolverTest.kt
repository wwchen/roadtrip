package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.ref.DbRefResolver
import ca.floo.roadtrip.service.ref.RefValue
import ca.floo.roadtrip.service.ref.resolve
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class DbAvailabilityTargetResolverTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedPoi(campgroundId: String?): Long =
        ctx
            .seedCatalogPoi(
                sourceId = "poi-${campgroundId ?: "none"}-${System.nanoTime()}",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "test",
                providerRefJson = campgroundId?.let { """{"recgov_id": "$it"}""" },
                bookingProvider = campgroundId?.let { "recgov" },
                bookingProviderRef = campgroundId,
            ).poiId

    private fun seedCampsite(
        siteId: String,
        poiId: Long,
    ): Long =
        ctx.seedCampsite(
            campgroundId = campgroundIdFor(poiId),
            vendor = "recgov",
            vendorId = siteId,
            name = "Site $siteId",
            bookingProvider = "recgov",
            bookingProviderRef = siteId,
        )

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private class NoopRecgovProvider : AvailabilityProvider {
        override val id: BookingProvider = BookingProvider.RECGOV
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private open class NoopCampflareProvider(
        private val enabled: Boolean,
    ) : AvailabilityProvider {
        override val id: BookingProvider = BookingProvider.CAMPFLARE
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = false,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = enabled

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private fun resolverFor(
        campsitesRepo: CampsiteRepo,
        providers: Map<String, AvailabilityProvider> =
            mapOf(
                "test" to NoopRecgovProvider(),
                "recgov" to NoopRecgovProvider(),
                "campflare" to NoopCampflareProvider(enabled = true),
            ),
    ): DbAvailabilityTargetResolver =
        DbAvailabilityTargetResolver(
            refResolver =
                ca.floo.roadtrip.service.ref
                    .DbRefResolver(ctx),
            ctx = ctx,
            campsitesRepo = campsitesRepo,
            availabilityProviders = AvailabilityProviderRegistry(providers),
            dateResolver = AvailabilityDateResolver(ctx),
            pollerRepo = AvailabilityPollerRepo(ctx),
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
                        bookingProvider = "campflare",
                        bookingProviderRef = "upper-pines-campground-447",
                    ).poiId
            val campgroundId = campgroundIdFor(poi)
            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campgroundId,
                    vendor = "campflare",
                    vendorId = "upper-pines-site-100",
                    name = "Campflare Site 100",
                    providerRefJson = """{"campflare_id":"upper-pines-site-100"}""",
                    bookingProvider = "campflare",
                    bookingProviderRef = "upper-pines-site-100",
                )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target = resolverFor(campsitesRepo).resolve(reservable)!!

            assertEquals("campflare", reservable.dataProviderRef.provider.id)
            assertEquals("upper-pines-site-100", reservable.dataProviderRef.serialize())
            assertEquals(poi, target.parentPoiId)
            assertEquals(BookingProvider.CAMPFLARE, target.provider.id)
            assertEquals("upper-pines-campground-447", parentRefKey(target.parentRef))
            assertEquals("upper-pines-site-100", target.catalogRef.vendorId)
        }

    @Test
    fun `resolve translates catalog ref through matching campsite booking ref`() =
        runBlocking {
            val poiId = seedDualVendorPoi()
            val campflareId = campgroundIdFor(poiId, "campflare")
            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campflareId,
                    vendor = "campflare",
                    vendorId = "upper-pines-site-100",
                    name = "Campflare Site 100",
                    bookingProvider = "recgov",
                    bookingProviderRef = "330257",
                )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target =
                resolverFor(
                    campsitesRepo = campsitesRepo,
                    providers =
                        mapOf(
                            "campflare" to NoopCampflareProvider(enabled = false),
                            "recgov" to NoopRecgovProvider(),
                        ),
                ).resolve(reservable)!!

            assertEquals(BookingProvider.RECGOV, target.provider.id)
            assertEquals("232447", parentRefKey(target.parentRef))
            assertEquals("330257", target.catalogRef.vendorId)
        }

    @Test
    fun `findProviderRefCandidates enumerates every campground linked to a POI`() {
        val poiId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO pois (poi_type, geom)
                    VALUES ('campground', ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326))
                    RETURNING id
                    """.trimIndent(),
                )!!
                .get("id", Long::class.java)

        val cg1 =
            ctx.seedCampground(
                name = "Upper Pines (campflare)",
                source = "campflare",
                sourceId = "upper-pines-campground-447",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-campground-447",
                sourcePayloadJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        val cg2 =
            ctx.seedCampground(
                name = "Upper Pines (recgov)",
                source = "recgov",
                sourceId = "232447",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
                sourcePayloadJson = """{"recgov_id":"232447"}""",
            )
        ctx.execute("INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (?, ?)", poiId, cg1)
        ctx.execute("INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (?, ?)", poiId, cg2)

        val resolver = DbRefResolver(ctx)
        val candidates = resolver.resolve<RefValue.CampgroundBookingRef>(RefValue.PoiId(poiId))
        assertEquals(
            listOf("campflare", "recgov"),
            candidates.map { it.ref.provider.id }.sorted(),
        )
    }

    @Test
    fun `resolve falls back to recgov when campflare provider is disabled`() =
        runBlocking {
            val poiId = seedDualVendorPoi()
            val campflareId = campgroundIdFor(poiId, "campflare")
            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campflareId,
                    vendor = "campflare",
                    vendorId = "upper-pines-site-100",
                    name = "Campflare Site 100",
                    providerRefJson = """{"campflare_id":"upper-pines-site-100"}""",
                    bookingProvider = "campflare",
                    bookingProviderRef = "upper-pines-site-100",
                )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target =
                resolverFor(
                    campsitesRepo = campsitesRepo,
                    providers =
                        mapOf(
                            "campflare" to NoopCampflareProvider(enabled = false),
                            "recgov" to NoopRecgovProvider(),
                        ),
                ).resolve(reservable)!!

            assertEquals("campflare", reservable.dataProviderRef.provider.id)
            assertEquals("upper-pines-site-100", reservable.dataProviderRef.serialize())
            assertEquals(BookingProvider.RECGOV, target.provider.id)
            assertEquals("232447", parentRefKey(target.parentRef))
        }

    @Test
    fun `resolve returns ordered candidate list for a dual-vendor POI`() =
        runBlocking {
            val poiId = seedDualVendorPoi()
            val campflareId = campgroundIdFor(poiId, "campflare")
            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campflareId,
                    vendor = "campflare",
                    vendorId = "upper-pines-site-100",
                    name = "Campflare Site 100",
                    bookingProvider = "campflare",
                    bookingProviderRef = "upper-pines-site-100",
                )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target =
                resolverFor(
                    campsitesRepo = campsitesRepo,
                    providers =
                        mapOf(
                            "campflare" to NoopCampflareProvider(enabled = true),
                            "recgov" to NoopRecgovProvider(),
                        ),
                ).resolve(reservable)!!

            assertEquals(2, target.candidates.size)
            assertEquals(BookingProvider.CAMPFLARE, target.candidates[0].provider.id)
            assertEquals(BookingProvider.RECGOV, target.candidates[1].provider.id)
            assertEquals(target.candidates[0].provider, target.provider)
            assertEquals(target.candidates[0].parentRef, target.parentRef)
            assertEquals(target.candidates[0].catalogRef, target.catalogRef)
            assertEquals("upper-pines-campground-447", parentRefKey(target.candidates[0].parentRef))
            assertEquals("232447", parentRefKey(target.candidates[1].parentRef))
        }

    @Test
    fun `resolve skips candidates whose provider is not registered`() =
        runBlocking {
            val poiId = seedDualVendorPoi()
            val campflareId = campgroundIdFor(poiId, "campflare")
            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campflareId,
                    vendor = "campflare",
                    vendorId = "upper-pines-site-100",
                    name = "Campflare Site 100",
                    bookingProvider = "campflare",
                    bookingProviderRef = "upper-pines-site-100",
                )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target =
                resolverFor(
                    campsitesRepo = campsitesRepo,
                    providers =
                        mapOf(
                            "recgov" to NoopRecgovProvider(),
                        ),
                ).resolve(reservable)!!

            assertEquals(1, target.candidates.size)
            assertEquals(BookingProvider.RECGOV, target.candidates[0].provider.id)
            assertEquals(BookingProvider.RECGOV, target.provider.id)
            assertEquals("232447", parentRefKey(target.parentRef))
        }

    @Test
    fun `resolve skips disabled provider candidates and falls back`() =
        runBlocking {
            val poiId = seedDualVendorPoi()
            val campflareId = campgroundIdFor(poiId, "campflare")
            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campflareId,
                    vendor = "campflare",
                    vendorId = "upper-pines-site-100",
                    name = "Campflare Site 100",
                    bookingProvider = "campflare",
                    bookingProviderRef = "upper-pines-site-100",
                )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target =
                resolverFor(
                    campsitesRepo = campsitesRepo,
                    providers =
                        mapOf(
                            "campflare" to NoopCampflareProvider(enabled = false),
                            "recgov" to NoopRecgovProvider(),
                        ),
                ).resolve(reservable)!!

            assertEquals(1, target.candidates.size)
            assertEquals(BookingProvider.RECGOV, target.provider.id)
            assertEquals("232447", parentRefKey(target.parentRef))
        }

    @Test
    fun `resolve returns null when no candidate survives`() =
        runBlocking {
            val poi =
                ctx
                    .seedCatalogPoi(
                        sourceId = "upper-pines-unknown-vendor",
                        name = "Upper Pines",
                        lon = -119.56,
                        lat = 37.74,
                        source = "recgov",
                        providerRefJson = """{"recgov_id":"232447"}""",
                    ).poiId

            val campsiteId =
                ctx.seedCampsite(
                    campgroundId = campgroundIdFor(poi),
                    vendor = "recgov",
                    vendorId = "site-100",
                    name = "Site 100",
                )

            val campsitesRepo = CampsiteRepo(ctx)
            val reservable = campsitesRepo.findById(campsiteId)!!
            val target =
                resolverFor(
                    campsitesRepo = campsitesRepo,
                    providers = mapOf("campflare" to NoopCampflareProvider(enabled = true)),
                ).resolve(reservable)

            assertEquals(null, target)
        }

    private fun seedDualVendorPoi(): Long {
        val poiId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO pois (poi_type, geom)
                    VALUES ('campground', ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326))
                    RETURNING id
                    """.trimIndent(),
                )!!
                .get("id", Long::class.java)
        val cg1 =
            ctx.seedCampground(
                name = "Upper Pines (campflare)",
                source = "campflare",
                sourceId = "upper-pines-campground-447",
                bookingProvider = "campflare",
                bookingProviderRef = "upper-pines-campground-447",
                sourcePayloadJson = """{"campflare_id":"upper-pines-campground-447"}""",
            )
        val cg2 =
            ctx.seedCampground(
                name = "Upper Pines (recgov)",
                source = "recgov",
                sourceId = "232447",
                bookingProvider = "recgov",
                bookingProviderRef = "232447",
                sourcePayloadJson = """{"recgov_id":"232447"}""",
            )
        ctx.execute("INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (?, ?)", poiId, cg1)
        ctx.execute("INSERT INTO poi_campgrounds (poi_id, campground_id) VALUES (?, ?)", poiId, cg2)
        return poiId
    }

    private fun campgroundIdFor(
        poiId: Long,
        source: String,
    ): Long =
        ctx
            .fetchOne(
                """
                SELECT cg.id
                FROM campgrounds cg
                JOIN poi_campgrounds pc ON pc.campground_id = cg.id
                WHERE pc.poi_id = ? AND cg.data_provider = ?
                """.trimIndent(),
                poiId,
                source,
            )!!
            .get("id", Long::class.java)
}
