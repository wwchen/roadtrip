package ca.floo.campsite.recgov.booker.availability

import ca.floo.campsite.recgov.booker.poller.Campsite
import ca.floo.roadtrip.repo.InMemoryPersistentCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CachedAvailabilityTest {
    private fun fakeMap() = mapOf("100" to Campsite("100", "A1", "Loop A", "STANDARD", 4, emptyList(), mapOf("2026-07-01" to "Available")))

    /** Mutable clock so tests can advance "now" past TTL. */
    private class TestClock(
        private var instant: Instant,
    ) : Clock() {
        override fun instant(): Instant = instant

        override fun withZone(zone: ZoneId): Clock = this

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        fun advance(d: Duration) {
            instant = instant.plus(d)
        }
    }

    @Test
    fun `cache miss fetches and cache hit does not refetch`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        fakeMap()
                    },
                    ttl = Duration.ofMinutes(10),
                )
            val a = cache.get("recgov", "232447", "2026-07-01")
            assertEquals(false, a.hit)
            val b = cache.get("recgov", "232447", "2026-07-01")
            assertEquals(true, b.hit)
            assertEquals(1, calls.get())
        }

    @Test
    fun `persistent cache survives cache instance restart`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val persistentCache = InMemoryPersistentCache()
            val first =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        fakeMap()
                    },
                    ttl = Duration.ofMinutes(10),
                    persistentCache = persistentCache,
                )
            first.get("recgov", "232447", "2026-07-01")

            val second =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        emptyMap()
                    },
                    ttl = Duration.ofMinutes(10),
                    persistentCache = persistentCache,
                )
            val restored = second.get("recgov", "232447", "2026-07-01")

            assertEquals(true, restored.hit)
            assertEquals(fakeMap(), restored.data)
            assertEquals(1, calls.get())
        }

    @Test
    fun `cache expires after TTL and refetches`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val clock = TestClock(Instant.parse("2026-06-05T10:00:00Z"))
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        fakeMap()
                    },
                    ttl = Duration.ofMinutes(10),
                    clock = clock,
                )
            cache.get("recgov", "232447", "2026-07-01")
            clock.advance(Duration.ofMinutes(11))
            val r = cache.get("recgov", "232447", "2026-07-01")
            assertEquals(false, r.hit)
            assertEquals(2, calls.get())
        }

    @Test
    fun `concurrent waiters coalesce to a single fetch`() =
        runBlocking {
            val calls = AtomicInteger(0)
            // Hold the fetcher until we've launched all 10 waiters; this proves
            // the cache coalesces even when the in-flight fetch is slow.
            val gate = Mutex(locked = true)
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        gate.withLock { } // wait for the test to release
                        fakeMap()
                    },
                )
            val outerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val launched =
                    (1..10).map {
                        outerScope.async { cache.get("recgov", "232447", "2026-07-01") }
                    }
                // Give all 10 a chance to enter cache.computeIfAbsent.
                delay(50)
                gate.unlock()
                launched.awaitAll()
                assertEquals(1, calls.get(), "10 concurrent waiters should share a single fetch")
            } finally {
                outerScope.cancel()
            }
        }

    @Test
    fun `failure evicts the entry so the next caller retries`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        val n = calls.incrementAndGet()
                        if (n == 1) error("rec.gov 5xx")
                        fakeMap()
                    },
                )
            assertFails { cache.get("recgov", "232447", "2026-07-01") }
            // Second call should retry, not see the cached failure.
            val r = cache.get("recgov", "232447", "2026-07-01")
            assertEquals(false, r.hit)
            assertEquals(2, calls.get())
        }

    @Test
    fun `force=true bypasses cache`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        fakeMap()
                    },
                )
            cache.get("recgov", "232447", "2026-07-01")
            cache.get("recgov", "232447", "2026-07-01", force = true)
            assertEquals(2, calls.get())
        }

    @Test
    fun `different keys are cached independently`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ ->
                        calls.incrementAndGet()
                        fakeMap()
                    },
                )
            cache.get("recgov", "232447", "2026-07-01")
            cache.get("recgov", "232447", "2026-08-01") // different month
            cache.get("recgov", "232448", "2026-07-01") // different campground
            cache.get("statepark", "232447", "2026-07-01") // different provider
            assertEquals(4, calls.get())
            assertEquals(4, cache.size())
        }

    @Test
    fun `age_seconds reflects cache hit age`() =
        runBlocking {
            val clock = TestClock(Instant.parse("2026-06-05T10:00:00Z"))
            val cache =
                CachedAvailability(
                    fetchMonth = { _, _ -> fakeMap() },
                    ttl = Duration.ofMinutes(10),
                    clock = clock,
                )
            cache.get("recgov", "232447", "2026-07-01")
            clock.advance(Duration.ofMinutes(3))
            val r = cache.get("recgov", "232447", "2026-07-01")
            assertEquals(true, r.hit)
            assertTrue(r.ageSeconds in 175..185, "expected ~180s, got ${r.ageSeconds}")
        }
}
