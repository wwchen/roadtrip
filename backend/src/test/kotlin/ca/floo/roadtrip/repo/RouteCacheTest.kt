package ca.floo.roadtrip.repo

import ca.floo.roadtrip.client.MapboxDirections
import ca.floo.roadtrip.client.RouteLeg
import ca.floo.roadtrip.client.RouteResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals

class RouteCacheTest {
    @Test
    fun `persistent cache restores route without refetching directions`() =
        runBlocking {
            val persistentCache = InMemoryPersistentCache()
            val waypoints = listOf(-123.1207 to 49.2827, -114.0719 to 51.0447)
            val response =
                RouteResponse(
                    coordinates = listOf(listOf(-123.1207, 49.2827), listOf(-114.0719, 51.0447)),
                    distanceMeters = 971_000.0,
                    durationSeconds = 37_000.0,
                    legs = listOf(RouteLeg(distanceMeters = 971_000.0, durationSeconds = 37_000.0)),
                )
            RouteCache(
                directions = MapboxDirections(token = null),
                ttl = Duration.ofMinutes(10),
                persistentCache = persistentCache,
            ).put(waypoints, response)

            val restored =
                RouteCache(
                    directions = MapboxDirections(token = null),
                    ttl = Duration.ofMinutes(10),
                    persistentCache = persistentCache,
                ).directions(waypoints)

            assertEquals(response, restored)
        }
}
