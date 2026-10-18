package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.FAKE_PROVIDER_HORIZON_DAYS
import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.testCampground
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val earliestBookable: LocalDate = LocalDate.of(2026, 8, 1)
private val fixedNow: Instant = Instant.parse("2026-07-31T12:00:00Z")
private const val DEFAULT_WINDOW_DAYS = 7L
private const val WIDE_WINDOW_DAYS = 60
private const val TIGHT_HORIZON_DAYS = 30
private const val RECGOV_REF = "232447"
private const val CAMPFLARE_REF = "upper-pines-447"
private val recgovTtl: Duration = Duration.ofMinutes(15)
private val campflareTtl: Duration = Duration.ofMinutes(45)

class AvailabilityReadPolicyTest {
    private val recgovCampground = testCampground(bookingProvider = "recgov", bookingProviderRef = RECGOV_REF)
    private val unclaimedCampground = testCampground(bookingProvider = null, bookingProviderRef = null)
    private val dateContext = PoiDateContext(timeZone = ZoneId.of("UTC"), earliestDate = earliestBookable)

    private fun policy(
        providers: List<AvailabilityProvider>,
        snapshotFreshnessTtl: (AvailabilityProvider) -> Duration = { recgovTtl },
    ): AvailabilityReadPolicy {
        val dateResolver = AvailabilityDateResolver(PoiRepo(DSL.using(SQLDialect.POSTGRES)))
        return AvailabilityReadPolicy(
            providers = providers,
            dateResolver = dateResolver,
            bookingHorizons = BookingHorizonResolver(providers, dateResolver),
            clock = Clock.fixed(fixedNow, ZoneOffset.UTC),
            snapshotFreshnessTtl = snapshotFreshnessTtl,
        )
    }

    private fun AvailabilityReadPolicy.readWindow(
        startDate: LocalDate? = null,
        endDate: LocalDate? = null,
        freshAtOrAfter: Instant? = null,
    ): AvailabilityRead = read(recgovCampground, dateContext, startDate, endDate, freshAtOrAfter)

    @Test
    fun `the target window defaults to seven days from the earliest bookable date`() {
        val read = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV))).readWindow()

        assertEquals(earliestBookable, read.windows.target.startDate)
        assertEquals(earliestBookable.plusDays(DEFAULT_WINDOW_DAYS), read.windows.target.endDate)
    }

    @Test
    fun `a requested start with no end gets the default span from that start`() {
        val start = earliestBookable.plusDays(10)

        val read = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV))).readWindow(startDate = start)

        assertEquals(start, read.windows.target.startDate)
        assertEquals(start.plusDays(DEFAULT_WINDOW_DAYS), read.windows.target.endDate)
    }

    @Test
    fun `the fetch window is the vendor's widest poll window`() {
        val cases =
            listOf(
                Triple(WIDE_WINDOW_DAYS, FAKE_PROVIDER_HORIZON_DAYS, WIDE_WINDOW_DAYS),
                Triple(FAKE_PROVIDER_HORIZON_DAYS, TIGHT_HORIZON_DAYS, TIGHT_HORIZON_DAYS),
            )

        cases.forEach { (maxPollWindowDays, horizonDays, expectedSpan) ->
            val provider =
                FakeAvailabilityProvider(
                    BookingProvider.RECGOV,
                    bookingHorizonDays = horizonDays,
                    maxPollWindowDays = maxPollWindowDays,
                )

            val read = policy(listOf(provider)).readWindow()

            assertEquals(earliestBookable, read.windows.fetch.startDate)
            assertEquals(earliestBookable.plusDays(expectedSpan.toLong()), read.windows.fetch.endDate)
        }
    }

    @Test
    fun `the fetch window is anchored at the requested start, not the earliest date`() {
        val start = earliestBookable.plusDays(20)

        val read = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV))).readWindow(startDate = start)

        assertEquals(start, read.windows.fetch.startDate)
        assertEquals(start.plusDays(WIDE_WINDOW_DAYS.toLong()), read.windows.fetch.endDate)
    }

    @Test
    fun `a window past the booking horizon is a bad date window`() {
        val policy = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV)))
        val latest = earliestBookable.plusDays(FAKE_PROVIDER_HORIZON_DAYS.toLong())

        val error =
            assertFailsWith<AvailabilityServiceError.BadDateWindow.BeyondBookingHorizon> {
                policy.readWindow(startDate = latest.minusDays(1), endDate = latest.plusDays(1))
            }

        assertEquals(latest, error.latestDate)
    }

    @Test
    fun `a start before the earliest bookable date is a bad date window`() {
        val policy = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV)))

        assertFailsWith<AvailabilityServiceError.BadDateWindow.StartBeforeEarliest> {
            policy.readWindow(startDate = earliestBookable.minusDays(1))
        }
    }

    @Test
    fun `the latest date is the serving provider's booking horizon`() {
        val read = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV))).readWindow()

        assertEquals(earliestBookable.plusDays(FAKE_PROVIDER_HORIZON_DAYS.toLong()), read.latestDate)
    }

    @Test
    fun `an unclaimed campground has no latest date and does not throw`() {
        val policy = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV)))

        assertNull(policy.latestDate(unclaimedCampground, dateContext))
        assertNull(policy.bookingHorizonDays(unclaimedCampground))
        assertEquals(emptyList(), policy.supportingProviders(unclaimedCampground))
        assertEquals(false, policy.pollingSupported(unclaimedCampground))
    }

    @Test
    fun `reading windows for an unclaimed campground is an unknown campground, not a missing latest date`() {
        val policy = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV)))

        assertFailsWith<AvailabilityServiceError.UnknownCampground> {
            policy.read(unclaimedCampground, dateContext, null, null, null)
        }
    }

    @Test
    fun `a disabled provider does not claim the campground`() {
        val policy = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV, enabled = false)))

        assertNull(policy.latestDate(recgovCampground, dateContext))
        assertFailsWith<AvailabilityServiceError.UnknownCampground> { policy.readWindow() }
    }

    @Test
    fun `freshness defaults to the serving provider's ttl before now`() {
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val campflare = FakeAvailabilityProvider(BookingProvider.CAMPFLARE)
        val ttl: (AvailabilityProvider) -> Duration = { if (it.id == BookingProvider.RECGOV) recgovTtl else campflareTtl }

        val read = policy(listOf(recgov, campflare), ttl).readWindow()

        assertEquals(fixedNow.minus(recgovTtl), read.freshAtOrAfter)
    }

    @Test
    fun `an explicit freshness cutoff overrides the ttl`() {
        val explicit = Instant.parse("2026-07-01T00:00:00Z")

        val read = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV))).readWindow(freshAtOrAfter = explicit)

        assertEquals(explicit, read.freshAtOrAfter)
    }

    @Test
    fun `the serving provider is the first that claims the campground`() {
        val campflare = FakeAvailabilityProvider(BookingProvider.CAMPFLARE)
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)

        val read = policy(listOf(campflare, recgov)).readWindow()

        assertEquals(BookingProvider.RECGOV, read.provider.id)
    }

    @Test
    fun `supporting providers are every enabled provider that claims the campground, in registry order`() {
        val campground = testCampground(bookingProvider = "campflare", bookingProviderRef = CAMPFLARE_REF)
        val campflare = FakeAvailabilityProvider(BookingProvider.CAMPFLARE)
        val recgov = FakeAvailabilityProvider(BookingProvider.RECGOV)
        val disabled = FakeAvailabilityProvider(BookingProvider.CAMPFLARE, enabled = false)

        val supporting = policy(listOf(disabled, recgov, campflare)).supportingProviders(campground)

        assertEquals(listOf(campflare), supporting)
    }

    @Test
    fun `polling is supported only when the serving provider can be polled`() {
        val pollable = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV, supportsInternalPolling = true)))
        val unpollable = policy(listOf(FakeAvailabilityProvider(BookingProvider.RECGOV, supportsInternalPolling = false)))

        assertEquals(true, pollable.pollingSupported(recgovCampground))
        assertEquals(false, unpollable.pollingSupported(recgovCampground))
    }
}
