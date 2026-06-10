package ca.floo.roadtrip.repo

import ca.floo.roadtrip.client.MapboxDirections
import ca.floo.roadtrip.client.RouteResponse
import ca.floo.roadtrip.config.ApiCacheEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// In-memory cache for Mapbox Directions responses keyed by waypoints.
// /api/route populates the cache as a side-effect of serving the FE; the
// matching /api/pois/on-route call (with the same waypoints) then reads the
// cached polyline and asks RouteCorridorRepo for the server-side corridor
// polygon, instead of asking the FE to ship a turf.buffer-derived polygon
// back over the wire.
//
// TTL is generous because the cache key already includes every waypoint; a
// route invalidates when the user changes any stop. Exhaustion strategy is
// simple — TTL eviction on read; no LRU. Steady-state memory is bounded by
// the number of distinct routes the user explores per session (typically <10).
class RouteCache(
    private val directions: MapboxDirections,
    private val ttl: Duration = ApiCacheEntity.ROUTE.defaultTtl,
    private val now: () -> Instant = Instant::now,
    private val persistentCache: PersistentCache = NoopPersistentCache,
    private val json: Json = Json,
) {
    private data class Entry(
        val response: RouteResponse,
        val expiresAt: Instant,
    )

    private val log = LoggerFactory.getLogger(RouteCache::class.java)
    private val store = ConcurrentHashMap<String, Entry>()

    /**
     * Look up a directions response by [waypoints]. Cache hit returns
     * immediately; miss falls back to [MapboxDirections.directions]. The
     * fresh response is cached on the way out.
     */
    suspend fun directions(waypoints: List<Pair<Double, Double>>): RouteResponse {
        val key = waypointsKey(waypoints)
        val nowInstant = now()
        store[key]?.let { entry ->
            if (entry.expiresAt.isAfter(nowInstant)) {
                log.debug("route cache hit: key={}", key)
                return entry.response
            }
            store.remove(key, entry)
        }
        persistentCache.get(NAMESPACE, key)?.let { persisted ->
            try {
                val response = json.decodeFromJsonElement(RouteResponse.serializer(), persisted.payload)
                store[key] = Entry(response, persisted.expiresAt)
                log.debug("route persistent cache hit: key={}", key)
                return response
            } catch (e: Exception) {
                log.warn("route persistent cache decode failed key={}", key)
                persistentCache.delete(NAMESPACE, key)
            }
        }
        log.debug("route cache miss: key={}", key)
        val fresh = directions.directions(waypoints)
        store[key] = Entry(fresh, nowInstant.plus(ttl))
        persistentCache.put(
            NAMESPACE,
            key,
            json.encodeToJsonElement(RouteResponse.serializer(), fresh),
            ttl,
        )
        return fresh
    }

    /** Exposed for /api/route to seed the cache after its own fetch. */
    fun put(
        waypoints: List<Pair<Double, Double>>,
        response: RouteResponse,
    ) {
        val key = waypointsKey(waypoints)
        store[key] = Entry(response, now().plus(ttl))
        persistentCache.put(
            NAMESPACE,
            key,
            json.encodeToJsonElement(RouteResponse.serializer(), response),
            ttl,
        )
    }

    val configured: Boolean get() = directions.configured

    private fun waypointsKey(waypoints: List<Pair<Double, Double>>): String =
        waypoints.joinToString(";") { (lng, lat) -> "%.6f,%.6f".format(lng, lat) }

    companion object {
        private val NAMESPACE = ApiCacheEntity.ROUTE.namespace
    }
}
