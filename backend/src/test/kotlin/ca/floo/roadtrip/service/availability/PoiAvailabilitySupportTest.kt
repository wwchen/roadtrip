package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
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
                reservationProviders =
                    ReservationProviderRegistry(
                        mapOf(
                            "campflare" to DecliningCampflareProvider(),
                            "federal-campgrounds" to NoopRecgovProvider(),
                        ),
                    ),
            )

        assertEquals(true, support.supports(row))
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

    private class NoopRecgovProvider : ReservationProvider {
        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
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

    private class DecliningCampflareProvider : ReservationProvider {
        override val id: ReservationProviderId = ReservationProviderId.CAMPFLARE
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
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
