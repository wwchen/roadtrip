package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TEST_CAMPSITE_ID = 7L
private const val TEST_RECGOV_SITE_ID = "recgov-site-7"
private const val TEST_RECGOV_PARENT_ID = "recgov-parent-1"
private const val TEST_CAMPFLARE_PARENT_ID = "campflare-parent-1"

class AvailabilityBookingTargetResolverTest {
    @Test
    fun `targetFor skips availability-only campflare candidate and returns recgov booking target`() {
        val registry = BookingProviderRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)
        val resolved =
            resolvedTarget(
                candidates =
                    listOf(
                        campflareCandidate(),
                        recgovCandidate(),
                    ),
            )

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, resolved)

        assertEquals(BookingProviderId.RECGOV, target?.providerId)
        assertEquals(ProviderRef.RecGov(TEST_RECGOV_PARENT_ID), target?.parentRef)
        assertEquals(CatalogCampsiteRef(TEST_CAMPSITE_ID, TEST_RECGOV_SITE_ID), target?.campsiteRef)
    }

    @Test
    fun `targetFor returns null when no candidate maps to supported booking provider`() {
        val registry = BookingProviderRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, resolvedTarget(candidates = listOf(campflareCandidate())))

        assertNull(target)
    }

    private class RecGovOnlyBookingProvider : BookingProvider {
        override val id: BookingProviderId = BookingProviderId.RECGOV

        override fun can(
            action: BookingAction,
            target: BookingTarget,
        ): Boolean =
            action == BookingAction.ADD_TO_CART &&
                target.providerId == BookingProviderId.RECGOV &&
                target.parentRef is ProviderRef.RecGov

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult = AddToCartResult.Unsupported
    }

    private class FakeAvailabilityProvider(
        override val id: AvailabilityProviderId,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsAvailability = true,
                pollableForAlerts = true,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private fun resolvedTarget(candidates: List<ProviderCandidate>): ResolvedAvailabilityTarget {
        val head = candidates.first()
        return ResolvedAvailabilityTarget(
            campsite = campsite(),
            provider = head.provider,
            parentRef = head.parentRef,
            catalogRef = head.catalogRef,
            parentPoiId = 100L,
            dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
            candidates = candidates,
        )
    }

    private fun campflareCandidate(): ProviderCandidate =
        ProviderCandidate(
            provider = FakeAvailabilityProvider(AvailabilityProviderId.CAMPFLARE),
            parentRef = ProviderRef.Campflare(TEST_CAMPFLARE_PARENT_ID),
            catalogRef = CatalogCampsiteRef(TEST_CAMPSITE_ID, TEST_CAMPFLARE_PARENT_ID),
        )

    private fun recgovCandidate(): ProviderCandidate =
        ProviderCandidate(
            provider = FakeAvailabilityProvider(AvailabilityProviderId.RECGOV),
            parentRef = ProviderRef.RecGov(TEST_RECGOV_PARENT_ID),
            catalogRef = CatalogCampsiteRef(TEST_CAMPSITE_ID, TEST_RECGOV_SITE_ID),
        )

    private fun campsite(): CampsiteAvailabilityTarget =
        CampsiteAvailabilityTarget(
            id = TEST_CAMPSITE_ID,
            vendor = "campflare",
            vendorId = TEST_CAMPFLARE_PARENT_ID,
            name = "Site 7",
            loop = null,
            siteType = null,
            raw = null,
        )
}
