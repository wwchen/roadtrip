package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.FAKE_PROVIDER_YEAR_HORIZON_DAYS
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapter
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TEST_CAMPSITE_ID = 7L
private const val TEST_RECGOV_PARENT_ID = "recgov-parent-1"
private const val TEST_CAMPFLARE_PARENT_ID = "campflare-parent-1"
private const val TEST_RECGOV_SITE_ID = "91788"

class AvailabilityBookingTargetResolverTest {
    private val campflareProvider =
        FakeAvailabilityProvider(
            id = BookingProvider.CAMPFLARE,
            bookingHorizonDays = FAKE_PROVIDER_YEAR_HORIZON_DAYS,
            parentRefOverride = { BookingProviderRef.Campflare(TEST_CAMPFLARE_PARENT_ID) },
        )
    private val recgovProvider =
        FakeAvailabilityProvider(
            id = BookingProvider.RECGOV,
            bookingHorizonDays = FAKE_PROVIDER_YEAR_HORIZON_DAYS,
        )

    @Test
    fun `targetFor skips availability-only campflare candidate and returns recgov booking target`() {
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)
        val resolved =
            resolvedTarget(
                candidates = listOf(campflareProvider, recgovProvider),
            )

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, resolved)

        assertEquals(BookingProvider.RECGOV, target?.providerId)
        assertEquals(BookingProviderRef.RecGov(TEST_RECGOV_PARENT_ID), target?.parentRef)
        assertEquals(TEST_CAMPSITE_ID, target?.campsiteId)
        assertEquals(TEST_CAMPFLARE_PARENT_ID, target?.vendorSiteId)
    }

    @Test
    fun `campflare availability with rec_gov booking resolves the campground's declared identity`() {
        // POI 8149 "Icicle Group Campground": the catalog row is Campflare, but
        // campgrounds.booking_provider says recgov/234784 and the campsite row
        // carries the rec.gov site id. Walking only the availability candidates
        // yields a Campflare ref no booking adapter serves, so booking_actions
        // came back empty for a campground that is perfectly bookable.
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)

        val target =
            resolver.targetFor(
                BookingAction.ADD_TO_CART,
                resolvedTarget(
                    candidates = listOf(campflareProvider),
                    campsite = crossProviderCampsite(),
                ),
            )

        assertEquals(BookingProvider.RECGOV, target?.providerId)
        assertEquals(BookingProviderRef.RecGov(TEST_RECGOV_PARENT_ID), target?.parentRef)
        // The cart needs the REC.GOV site id, not the Campflare catalog uuid.
        assertEquals(TEST_RECGOV_SITE_ID, target?.vendorSiteId)
    }

    @Test
    fun `a cross-provider campsite with no rec_gov site ref is correctly unbookable`() {
        // The campground declares rec.gov booking but this particular site was
        // never linked to a rec.gov id — there is nothing to put in a cart.
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)

        val target =
            resolver.targetFor(
                BookingAction.ADD_TO_CART,
                resolvedTarget(
                    candidates = listOf(campflareProvider),
                    campsite = crossProviderCampsite(recgovSiteId = null),
                ),
            )

        assertNull(target)
    }

    @Test
    fun `a declared ref no adapter serves falls through to the candidate walk untouched`() {
        // Pins the constraint the declared-ref path depends on: it reads the
        // campsite's stored ref raw, bypassing per-provider vendorSiteIdFor
        // overrides. That is safe only because a provider with no registered
        // booking adapter never reaches the cart through this path — its
        // declared ref finds nothing and the candidate walk decides instead.
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)
        val aspiraDeclared =
            resolvedTarget(candidates = listOf(recgovProvider))
                .let { base ->
                    base.copy(
                        campground =
                            campground(
                                bookingProvider = "aspira",
                                bookingProviderRef = "bc:1:2:3",
                                dataProviderRef = DataProviderRef.RecGov(id = TEST_RECGOV_PARENT_ID),
                            ),
                        campsite =
                            campsiteFixture(
                                id = TEST_CAMPSITE_ID,
                                vendor = "recgov",
                                vendorId = "site-7",
                                name = "Site 7",
                                loopName = null,
                                kind = null,
                                sourcePayload = null,
                                bookingProvider = "aspira",
                                // Aspira's structured ref. If the declared path
                                // ever served it, THIS is what would reach a cart.
                                bookingProviderRef = "bc:1:2:3",
                            ),
                    )
                }

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, aspiraDeclared)

        // Null, and that is the point. No aspira adapter is registered, so the
        // declared ref finds none; the candidate walk then asks the recgov
        // provider, whose default parentRefFor reads the same aspira columns
        // and also yields an aspira ref. Unbookable — and, load-bearingly,
        // "bc:1:2:3" never becomes a vendorSiteId anywhere.
        assertNull(target)
    }

    @Test
    fun `targetFor returns null when no candidate maps to supported booking provider`() {
        val registry = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider()))
        val resolver = AvailabilityBookingTargetResolver(registry)

        val target = resolver.targetFor(BookingAction.ADD_TO_CART, resolvedTarget(candidates = listOf(campflareProvider)))

        assertNull(target)
    }

    private class RecGovOnlyBookingProvider : BookingAdapter {
        override val id: BookingProvider = BookingProvider.RECGOV

        override fun targetFor(
            parentRef: BookingProviderRef,
            campsiteId: Long,
            vendorSiteId: String,
        ): BookingTarget? {
            if (parentRef !is BookingProviderRef.RecGov) return null
            return BookingTarget(
                providerId = id,
                parentRef = parentRef,
                campsiteId = campsiteId,
                vendorSiteId = vendorSiteId,
            )
        }

        override fun can(
            action: BookingAction,
            target: BookingTarget,
        ): Boolean =
            action == BookingAction.ADD_TO_CART &&
                target.providerId == BookingProvider.RECGOV &&
                target.parentRef is BookingProviderRef.RecGov

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult = AddToCartResult.Unsupported
    }

    private fun resolvedTarget(
        candidates: List<AvailabilityProvider>,
        campsite: Campsite = campsite(),
    ): ResolvedAvailabilityTarget {
        val cg =
            campground(
                bookingProvider = "recgov",
                bookingProviderRef = TEST_RECGOV_PARENT_ID,
                dataProviderRef = DataProviderRef.RecGov(id = TEST_RECGOV_PARENT_ID),
            )
        return ResolvedAvailabilityTarget(
            campsite = campsite,
            provider = candidates.first(),
            campground = cg,
            parentPoiId = 100L,
            dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
            candidates = candidates,
        )
    }

    private fun campground(
        bookingProvider: String?,
        bookingProviderRef: String?,
        dataProviderRef: DataProviderRef = DataProviderRef.Campflare(id = TEST_CAMPFLARE_PARENT_ID),
    ): Campground =
        Campground(
            id = 1L,
            name = "Test Campground",
            status = null,
            statusDescription = null,
            kind = null,
            shortDescription = null,
            mediumDescription = null,
            longDescription = null,
            location = null,
            defaultCampsiteSchedule = JsonNull,
            amenities = JsonNull,
            maxRvLength = null,
            maxTrailerLength = null,
            hasPullThroughSites = null,
            bigRigFriendly = null,
            reservationUrl = null,
            links = emptyList(),
            photos = emptyList(),
            alerts = JsonNull,
            price = JsonNull,
            cellService = JsonNull,
            management = null,
            contact = null,
            connections = JsonNull,
            metadata = JsonNull,
            sourcePayload = JsonNull,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
            dataProviderRef = dataProviderRef,
            bookingProvider = bookingProvider,
            bookingProviderRef = bookingProviderRef,
        )

    /** A Campflare catalog row whose booking identity is rec.gov's. */
    private fun crossProviderCampsite(recgovSiteId: String? = TEST_RECGOV_SITE_ID): Campsite =
        campsiteFixture(
            id = TEST_CAMPSITE_ID,
            vendor = "campflare",
            vendorId = "eb203fd1-6b28-4cb1-9d67-cb657299a825",
            name = "Icicle Group Site",
            loopName = null,
            kind = null,
            sourcePayload = null,
            bookingProvider = recgovSiteId?.let { BookingProvider.RECGOV.id },
            bookingProviderRef = recgovSiteId,
        )

    private fun campsite(): Campsite =
        campsiteFixture(
            id = TEST_CAMPSITE_ID,
            vendor = "campflare",
            vendorId = TEST_CAMPFLARE_PARENT_ID,
            name = "Site 7",
            loopName = null,
            kind = null,
            sourcePayload = null,
        )
}
