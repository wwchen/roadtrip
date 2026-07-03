package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.references.API_CACHE
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApiCacheRepoTest : SharedDbTest() {
    private class TestClock(
        private var instant: Instant,
    ) : Clock() {
        override fun instant(): Instant = instant

        override fun withZone(zone: ZoneId): Clock = this

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        fun advance(duration: Duration) {
            instant = instant.plus(duration)
        }
    }

    @BeforeEach
    fun reset() {
        ctx.deleteFrom(API_CACHE).execute()
    }

    @Test
    fun `stores jsonb payloads and expires them by ttl`() {
        val clock = TestClock(Instant.parse("2026-06-09T12:00:00Z"))
        val repo = ApiCacheRepo(ctx, clock)
        val payload =
            buildJsonObject {
                put("status", "cached")
                put("count", 2)
            }

        repo.put("unit", "example", payload, Duration.ofMinutes(5))
        val hit = repo.get("unit", "example")
        val status =
            hit
                ?.payload
                ?.jsonObject
                ?.get("status")
                ?.jsonPrimitive
                ?.content

        assertEquals(payload, hit?.payload)
        assertEquals("cached", status)
        assertEquals(300, hit?.ttlSeconds())

        clock.advance(Duration.ofMinutes(6))

        assertNull(repo.get("unit", "example"))
        assertEquals(0, ctx.fetchCount(API_CACHE))
    }
}
