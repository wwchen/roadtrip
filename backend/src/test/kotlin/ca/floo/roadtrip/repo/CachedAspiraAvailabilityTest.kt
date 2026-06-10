package ca.floo.roadtrip.repo

import ca.floo.roadtrip.client.AspiraAvailability
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class CachedAspiraAvailabilityTest {
    private val availability =
        AspiraAvailability(
            mapId = 42,
            parkRollup = listOf(0, 1, 2),
            byMapLink = mapOf("100" to listOf(1, 1, 0)),
        )

    @Test
    fun `persistent cache survives cache instance restart`() =
        runBlocking {
            val calls = AtomicInteger(0)
            val persistentCache = InMemoryPersistentCache()
            val first =
                CachedAspiraAvailability(
                    fetcher = { _, _, _, _ ->
                        calls.incrementAndGet()
                        availability
                    },
                    ttl = Duration.ofMinutes(10),
                    persistentCache = persistentCache,
                )
            first.get("example.goaspira.com", 42, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"))

            val second =
                CachedAspiraAvailability(
                    fetcher = { _, _, _, _ ->
                        calls.incrementAndGet()
                        availability.copy(parkRollup = emptyList())
                    },
                    ttl = Duration.ofMinutes(10),
                    persistentCache = persistentCache,
                )
            val restored = second.get("example.goaspira.com", 42, LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"))

            assertEquals(true, restored.hit)
            assertEquals(availability, restored.data)
            assertEquals(1, calls.get())
        }
}
