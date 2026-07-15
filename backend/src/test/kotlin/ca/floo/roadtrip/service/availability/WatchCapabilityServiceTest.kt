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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_PARENT_POI_ID = 100L

class WatchCapabilityServiceTest {
    @Test
    fun `supports add to cart when every scoped campsite resolves to a supported booking target`() {
        val campsiteA = campsite(1L, "site-1")
        val campsiteB = campsite(2L, "site-2")
        val service = service(campsiteA, campsiteB)

        val support = service.bookingSupportFor(BookingAction.ADD_TO_CART, listOf(campsiteA, campsiteB))

        assertTrue(support.supported)
        assertEquals(2, support.scopedCount)
        assertEquals(0, support.unsupportedCount)
        assertEquals(setOf(BookingAction.ADD_TO_CART), service.supportedBookingActions(listOf(campsiteA, campsiteB)))
        assertEquals(
            listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.ATC),
            service.supportedTriggerKinds(listOf(campsiteA, campsiteB)),
        )
    }

    @Test
    fun `does not support add to cart when any scoped campsite is unsupported`() {
        val supported = campsite(1L, "site-1")
        val unsupported = campsite(2L, "")
        val service = service(supported, unsupported)

        val support = service.bookingSupportFor(BookingAction.ADD_TO_CART, listOf(supported, unsupported))

        assertFalse(support.supported)
        assertEquals(2, support.scopedCount)
        assertEquals(1, support.unsupportedCount)
        assertEquals(emptySet(), service.supportedBookingActions(listOf(supported, unsupported)))
        assertEquals(listOf(AvailabilityTriggerKinds.SLACK_NOTIFY), service.supportedTriggerKinds(listOf(supported, unsupported)))
    }

    @Test
    fun `empty scope does not support add to cart`() {
        val service = service()

        val support = service.bookingSupportFor(BookingAction.ADD_TO_CART, emptyList())

        assertFalse(support.supported)
        assertEquals(0, support.scopedCount)
        assertEquals(0, support.unsupportedCount)
        assertFalse(service.internalPollingSupportFor(emptyList()).supported)
        assertEquals(emptyList(), service.supportedTriggerKinds(emptyList()))
    }

    @Test
    fun `does not support watch triggers when provider cannot be internally polled`() {
        val campsite = campsite(1L, "site-1")
        val service = service(campsites = listOf(campsite), supportsInternalPolling = false)

        val support = service.internalPollingSupportFor(listOf(campsite))

        assertFalse(support.supported)
        assertEquals(1, support.scopedCount)
        assertEquals(1, support.unsupportedCount)
        assertEquals(setOf(BookingAction.ADD_TO_CART), service.supportedBookingActions(listOf(campsite)))
        assertEquals(emptyList(), service.supportedTriggerKinds(listOf(campsite)))
    }

    @Test
    fun `supports watch triggers when a later provider candidate can be internally polled`() {
        val campsite = campsite(1L, "site-1")
        val service =
            service(
                campsites = listOf(campsite),
                supportsInternalPolling = false,
                fallbackSupportsInternalPolling = true,
            )

        val support = service.internalPollingSupportFor(listOf(campsite))

        assertTrue(support.supported)
        assertEquals(1, support.scopedCount)
        assertEquals(0, support.unsupportedCount)
        assertEquals(setOf(BookingAction.ADD_TO_CART), service.supportedBookingActions(listOf(campsite)))
        assertEquals(
            listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.ATC),
            service.supportedTriggerKinds(listOf(campsite)),
        )
    }

    private fun service(vararg campsites: CampsiteAvailabilityTarget): WatchCapabilityService = service(campsites = campsites.toList())

    private fun service(
        campsites: List<CampsiteAvailabilityTarget>,
        supportsInternalPolling: Boolean = true,
        fallbackSupportsInternalPolling: Boolean? = null,
    ): WatchCapabilityService {
        val registry = BookingProviderRegistry(listOf(RecGovOnlyBookingProvider))
        return WatchCapabilityService(
            availabilityTargets = FakeTargetResolver(campsites, supportsInternalPolling, fallbackSupportsInternalPolling),
            bookingTargets = AvailabilityBookingTargetResolver(registry),
        )
    }

    private object RecGovOnlyBookingProvider : BookingProvider {
        override val id: BookingProviderId = BookingProviderId.RECGOV

        override fun targetFor(
            parentRef: ProviderRef,
            campsiteRef: CatalogCampsiteRef,
        ): BookingTarget? {
            if (parentRef !is ProviderRef.RecGov) return null
            return BookingTarget(
                providerId = id,
                parentRef = parentRef,
                campsiteRef = campsiteRef,
            )
        }

        override fun can(
            action: BookingAction,
            target: BookingTarget,
        ): Boolean =
            action == BookingAction.ADD_TO_CART &&
                target.providerId == BookingProviderId.RECGOV &&
                target.parentRef is ProviderRef.RecGov &&
                target.campsiteRef.vendorId.isNotBlank()

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult = AddToCartResult.Unsupported
    }

    private class FakeTargetResolver(
        private val campsites: List<CampsiteAvailabilityTarget>,
        supportsInternalPolling: Boolean,
        private val fallbackSupportsInternalPolling: Boolean?,
    ) : AvailabilityTargetResolver {
        private val byId = campsites.associateBy { it.id }
        private val providerId =
            if (fallbackSupportsInternalPolling == null) {
                AvailabilityProviderId.RECGOV
            } else {
                AvailabilityProviderId.CAMPFLARE
            }
        private val provider = FakeAvailabilityProvider(providerId, supportsInternalPolling)
        private val fallbackProvider =
            fallbackSupportsInternalPolling?.let { FakeAvailabilityProvider(AvailabilityProviderId.RECGOV, it) }

        override fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget? {
            val known = byId[campsite.id] ?: return null
            val candidate =
                ProviderCandidate(
                    provider = provider,
                    parentRef =
                        if (providerId == AvailabilityProviderId.CAMPFLARE) {
                            ProviderRef.Campflare("facility-1")
                        } else {
                            ProviderRef.RecGov("facility-1")
                        },
                    catalogRef = CatalogCampsiteRef(campsiteId = known.id, vendorId = known.vendorId),
                )
            val fallbackCandidate =
                fallbackProvider?.let {
                    ProviderCandidate(
                        provider = it,
                        parentRef = ProviderRef.RecGov("facility-1"),
                        catalogRef = CatalogCampsiteRef(campsiteId = known.id, vendorId = known.vendorId),
                    )
                }
            val candidates = listOfNotNull(candidate, fallbackCandidate)
            return ResolvedAvailabilityTarget(
                campsite = known,
                provider = candidate.provider,
                parentRef = candidate.parentRef,
                catalogRef = candidate.catalogRef,
                parentPoiId = TEST_PARENT_POI_ID,
                dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
                candidates = candidates,
            )
        }
    }

    private class FakeAvailabilityProvider(
        override val id: AvailabilityProviderId,
        supportsInternalPolling: Boolean,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = supportsInternalPolling,
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

    private fun campsite(
        id: Long,
        vendorId: String,
    ): CampsiteAvailabilityTarget =
        CampsiteAvailabilityTarget(
            id = id,
            vendor = "recgov",
            vendorId = vendorId,
            name = "Site $id",
            loop = null,
            siteType = null,
            raw = null,
        )
}
