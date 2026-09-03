package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FailoverAvailabilityFetcherTest {
    private val window = ResolvedDateWindow(LocalDate.parse("2026-07-17"), LocalDate.parse("2026-07-31"))
    private val testCampground by lazy { fakeCampground("232447") }

    /** Mutable virtual clock (mirrors ProviderCooldownTrackerTest's FakeClock). */
    private class FakeClock(
        start: String = "2026-07-09T12:00:00Z",
    ) {
        var now: Instant = Instant.parse(start)
            private set

        fun advance(millis: Long) {
            now = now.plusMillis(millis)
        }
    }

    /** Fake provider whose `catalogAvailability` behaviour is scripted per call. */
    private open class ScriptedProvider(
        override val id: BookingProvider,
        // A queue of "return this batch" or "throw this" behaviours per call.
        private val script: MutableList<Behaviour> = mutableListOf(),
    ) : AvailabilityProvider {
        var calls: Int = 0
            private set
        var lastCampsites: List<Campsite>? = null
            private set

        sealed class Behaviour {
            data class ReturnBatch(
                val batch: AvailabilityObservationBatch,
            ) : Behaviour()

            data class Throw(
                val error: Throwable,
            ) : Behaviour()
        }

        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            campground: Campground,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")

        override suspend fun catalogAvailability(
            campground: Campground,
            campsites: List<Campsite>,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch {
            calls++
            lastCampsites = campsites
            val next = script.removeAt(0)
            return when (next) {
                is Behaviour.ReturnBatch -> next.batch
                is Behaviour.Throw -> throw next.error
            }
        }

        fun scriptReturns(batch: AvailabilityObservationBatch) {
            script += Behaviour.ReturnBatch(batch)
        }

        fun scriptThrows(error: Throwable) {
            script += Behaviour.Throw(error)
        }
    }

    private fun emptyBatch(providerLabel: String = "recgov"): AvailabilityObservationBatch =
        AvailabilityObservationBatch(
            provider = providerLabel,
            startDate = window.startDate,
            endDate = window.endDate,
            observations = emptyList(),
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        )

    private fun campsite(
        id: Long,
        vendor: String = "recgov",
        vendorId: String = id.toString(),
    ): Campsite =
        campsiteFixture(
            id = id,
            vendor = vendor,
            vendorId = vendorId,
            name = "",
            loopName = null,
            kind = null,
            sourcePayload = null,
        )

    private fun fakeCampground(parentId: String): Campground =
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
            dataProviderRef = DataProviderRef.RecGov(id = parentId),
            bookingProvider = "recgov",
            bookingProviderRef = parentId,
        )

    private fun trackerWith(
        clock: FakeClock,
        cooldownSeconds: Long = 60L,
    ): ProviderCooldownTracker = ProviderCooldownTracker(cooldown = Duration.ofSeconds(cooldownSeconds), clock = { clock.now })

    private fun fetcherWith(
        tracker: ProviderCooldownTracker,
        clock: FakeClock,
    ): FailoverAvailabilityFetcher = FailoverAvailabilityFetcher(cooldowns = tracker, clock = { clock.now })

    @Test
    fun `first candidate succeeds — single attempt, servedBy first`() =
        runBlocking {
            val clock = FakeClock()
            val tracker = trackerWith(clock)
            val provider = ScriptedProvider(BookingProvider.RECGOV).apply { scriptReturns(emptyBatch()) }
            val result =
                fetcherWith(tracker, clock).fetch(
                    providers = listOf(provider),
                    campground = testCampground,
                    campsites = listOf(campsite(1L)),
                    window = window,
                )

            assertNotNull(result.batch)
            assertEquals(BookingProvider.RECGOV, result.servedBy)
            assertEquals(1, result.attempts.size)
            assertEquals(FetchOutcome.OK, result.attempts.single().outcome)
            assertEquals(1, provider.calls)
        }

    @Test
    fun `first rate-limited then second OK — two attempts, cooldown recorded, servedBy second`() =
        runBlocking {
            val clock = FakeClock()
            val tracker = trackerWith(clock)
            val cooling =
                ScriptedProvider(BookingProvider.RECGOV).apply {
                    scriptThrows(AvailabilityProviderError.RateLimited(RuntimeException("429")))
                }
            val healthy =
                ScriptedProvider(BookingProvider.CAMPFLARE).apply { scriptReturns(emptyBatch("campflare")) }
            val result =
                fetcherWith(tracker, clock).fetch(
                    providers = listOf(cooling, healthy),
                    campground = testCampground,
                    campsites = listOf(campsite(1L)),
                    window = window,
                )

            assertNotNull(result.batch)
            assertEquals(BookingProvider.CAMPFLARE, result.servedBy)
            assertEquals(2, result.attempts.size)
            assertEquals(FetchOutcome.RATE_LIMITED, result.attempts[0].outcome)
            assertEquals(FetchOutcome.OK, result.attempts[1].outcome)
            assertTrue(tracker.isCooling(BookingProvider.RECGOV), "rate-limited provider should be cooling")
            assertFalse(tracker.isCooling(BookingProvider.CAMPFLARE), "succeeded provider not cooling")
        }

    @Test
    fun `all candidates retryable-fail — null batch, cooldowns recorded for all`() =
        runBlocking {
            val clock = FakeClock()
            val tracker = trackerWith(clock)
            val a =
                ScriptedProvider(BookingProvider.RECGOV).apply {
                    scriptThrows(AvailabilityProviderError.RateLimited())
                }
            val b =
                ScriptedProvider(BookingProvider.CAMPFLARE).apply {
                    scriptThrows(AvailabilityProviderError.UpstreamUnavailable(RuntimeException("500")))
                }
            val c =
                ScriptedProvider(BookingProvider.ASPIRA).apply {
                    scriptThrows(AvailabilityProviderError.UpstreamBlocked())
                }

            val result =
                fetcherWith(tracker, clock).fetch(
                    providers = listOf(a, b, c),
                    campground = testCampground,
                    campsites = listOf(campsite(1L)),
                    window = window,
                )

            assertNull(result.batch)
            assertNull(result.servedBy)
            assertEquals(3, result.attempts.size)
            assertEquals(FetchOutcome.RATE_LIMITED, result.attempts[0].outcome)
            assertEquals(FetchOutcome.UPSTREAM_5XX, result.attempts[1].outcome)
            assertEquals(FetchOutcome.BLOCKED, result.attempts[2].outcome)
            assertTrue(tracker.isCooling(BookingProvider.RECGOV))
            assertTrue(tracker.isCooling(BookingProvider.CAMPFLARE))
            assertTrue(tracker.isCooling(BookingProvider.ASPIRA))
        }

    @Test
    fun `OTHER outcome on first candidate — single attempt, no failover`() =
        runBlocking {
            val clock = FakeClock()
            val tracker = trackerWith(clock)
            val first =
                ScriptedProvider(BookingProvider.RECGOV).apply {
                    scriptThrows(IllegalStateException("boom"))
                }
            val second =
                ScriptedProvider(BookingProvider.CAMPFLARE).apply { scriptReturns(emptyBatch("campflare")) }

            val result =
                fetcherWith(tracker, clock).fetch(
                    providers = listOf(first, second),
                    campground = testCampground,
                    campsites = listOf(campsite(1L)),
                    window = window,
                )

            assertNull(result.batch)
            assertNull(result.servedBy)
            assertEquals(1, result.attempts.size)
            assertEquals(FetchOutcome.OTHER, result.attempts.single().outcome)
            assertEquals("boom", result.attempts.single().error)
            assertEquals(0, second.calls, "OTHER stops the walk — the second candidate is never tried")
            assertFalse(tracker.isCooling(BookingProvider.RECGOV), "OTHER does not cool the provider")
        }

    @Test
    fun `sole cooling candidate is still tried`() =
        runBlocking {
            val clock = FakeClock()
            val tracker = trackerWith(clock)
            // Pre-cool the only candidate: sortHealthyFirst demotes but never
            // drops. The fetcher must still call it.
            tracker.recordFailure(BookingProvider.RECGOV)
            val provider = ScriptedProvider(BookingProvider.RECGOV).apply { scriptReturns(emptyBatch()) }

            val result =
                fetcherWith(tracker, clock).fetch(
                    providers = listOf(provider),
                    campground = testCampground,
                    campsites = listOf(campsite(1L)),
                    window = window,
                )

            assertEquals(1, provider.calls, "sole cooling candidate must still be tried")
            assertNotNull(result.batch)
            assertEquals(BookingProvider.RECGOV, result.servedBy)
            assertFalse(tracker.isCooling(BookingProvider.RECGOV), "success cleared the cooldown")
        }

    @Test
    fun `empty candidates list — empty attempts, null batch, null servedBy`() =
        runBlocking {
            val clock = FakeClock()
            val tracker = trackerWith(clock)

            val result =
                fetcherWith(tracker, clock).fetch(
                    providers = emptyList(),
                    campground = testCampground,
                    campsites = emptyList(),
                    window = window,
                )

            assertNull(result.batch)
            assertNull(result.servedBy)
            assertTrue(result.attempts.isEmpty())
        }

    @Test
    fun `AvailabilityProviderError is mapped to its FetchOutcome via toFetchOutcome`() =
        runBlocking {
            val clock = FakeClock()
            val tracker = trackerWith(clock)
            val thrown = AvailabilityProviderError.RateLimited(RuntimeException("429"))
            val provider =
                ScriptedProvider(BookingProvider.RECGOV).apply {
                    scriptThrows(thrown)
                }

            val result =
                fetcherWith(tracker, clock).fetch(
                    providers = listOf(provider),
                    campground = testCampground,
                    campsites = listOf(campsite(1L)),
                    window = window,
                )

            assertEquals(1, result.attempts.size)
            assertEquals(FetchOutcome.RATE_LIMITED, result.attempts.single().outcome, "RateLimited maps to RATE_LIMITED")
            assertSame(thrown, result.attempts.single().providerError)
            assertTrue(tracker.isCooling(BookingProvider.RECGOV), "retryable failure cools the provider")
        }

    @Test
    fun `attempt durationMs is measured from clock ticks`() =
        runBlocking {
            val clock = FakeClock()
            val tracker = trackerWith(clock)
            // Wrap the scripted provider so the clock ticks forward inside the call.
            val provider =
                object : ScriptedProvider(BookingProvider.RECGOV) {
                    init {
                        scriptReturns(emptyBatch())
                    }

                    override suspend fun catalogAvailability(
                        campground: Campground,
                        campsites: List<Campsite>,
                        startDate: LocalDate,
                        endDate: LocalDate,
                    ): AvailabilityObservationBatch {
                        clock.advance(125)
                        return super.catalogAvailability(campground, campsites, startDate, endDate)
                    }
                }

            val result =
                fetcherWith(tracker, clock).fetch(
                    providers = listOf(provider),
                    campground = testCampground,
                    campsites = listOf(campsite(1L)),
                    window = window,
                )

            assertEquals(125, result.attempts.single().durationMs)
        }
}
