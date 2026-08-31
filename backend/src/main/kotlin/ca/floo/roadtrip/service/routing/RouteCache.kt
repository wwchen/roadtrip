package ca.floo.roadtrip.service.routing

import ca.floo.roadtrip.client.mapbox.MapboxDirections
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.model.routing.RouteResponse
import ca.floo.roadtrip.repo.NoopPersistentCache
import ca.floo.roadtrip.repo.PersistentCache
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
// cached polyline and asks RouteCorridorService for the server-side corridor
// polygon, instead of asking the FE to ship a turf.buffer-derived polygon
// back over the wire.
//
// TTL is generous because the cache key already includes every waypoint; a
// route invalidates when the user changes any stop. Steady-state memory is the
// distinct routes explored per session (typically <10), but the key is minted
// from unauthenticated coordinates, so every insert first sweeps expired
// entries and then evicts down to MAX_ENTRIES — an unbounded map here is a
// memory-exhaustion primitive for anyone able to call /api/route in a loop.
private const val MAX_ENTRIES = 500

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

    val configured: Boolean get() = directions.configured

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
        persistentCache.get(namespace, key)?.let { persisted ->
            try {
                val response = json.decodeFromJsonElement(RouteResponse.serializer(), persisted.payload)
                remember(key, Entry(response, persisted.expiresAt))
                log.debug("route persistent cache hit: key={}", key)
                return response
            } catch (e: Exception) {
                log.warn("route persistent cache decode failed key={}", key)
                persistentCache.delete(namespace, key)
            }
        }
        log.debug("route cache miss: key={}", key)
        val fresh = directions.directions(waypoints)
        remember(key, Entry(fresh, nowInstant.plus(ttl)))
        persistentCache.put(
            namespace,
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
        remember(key, Entry(response, now().plus(ttl)))
        persistentCache.put(
            namespace,
            key,
            json.encodeToJsonElement(RouteResponse.serializer(), response),
            ttl,
        )
    }

    /** Insert, then keep the map at [MAX_ENTRIES]: expired entries first, then the soonest to expire. */
    private fun remember(
        key: String,
        entry: Entry,
    ) {
        store[key] = entry
        if (store.size <= MAX_ENTRIES) return
        val nowInstant = now()
        store.entries.removeIf { !it.value.expiresAt.isAfter(nowInstant) }
        while (store.size > MAX_ENTRIES) {
            val oldest = store.entries.minByOrNull { it.value.expiresAt } ?: return
            store.remove(oldest.key, oldest.value)
        }
    }

    private fun waypointsKey(waypoints: List<Pair<Double, Double>>): String =
        waypoints.joinToString(";") { (lng, lat) -> "%.6f,%.6f".format(lng, lat) }

    companion object {
        private val namespace = ApiCacheEntity.ROUTE.namespace
    }
}
