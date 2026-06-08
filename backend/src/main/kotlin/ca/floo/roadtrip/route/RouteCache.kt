package ca.floo.roadtrip.route

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// In-memory cache for Mapbox Directions responses keyed by waypoints.
// /api/route populates the cache as a side-effect of serving the FE; the
// matching /api/pois call (with the same waypoints) then reads the cached
// polyline server-side and buffers it for ST_Intersects, instead of asking
// the FE to ship a turf.buffer-derived polygon back over the wire (which
// regenerates several KB of data the BE could compute in tens of microseconds).
//
// TTL is generous because the cache key already includes every waypoint; a
// route invalidates when the user changes any stop. Exhaustion strategy is
// simple — TTL eviction on read; no LRU. Steady-state memory is bounded by
// the number of distinct routes the user explores per session (typically <10).
class RouteCache(
    private val directions: MapboxDirections,
    private val ttl: Duration = Duration.ofMinutes(10),
    private val now: () -> Instant = Instant::now,
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
        log.debug("route cache miss: key={}", key)
        val fresh = directions.directions(waypoints)
        store[key] = Entry(fresh, nowInstant.plus(ttl))
        return fresh
    }

    /** Exposed for /api/route to seed the cache after its own fetch. */
    fun put(
        waypoints: List<Pair<Double, Double>>,
        response: RouteResponse,
    ) {
        store[waypointsKey(waypoints)] = Entry(response, now().plus(ttl))
    }

    val configured: Boolean get() = directions.configured

    private fun waypointsKey(waypoints: List<Pair<Double, Double>>): String =
        waypoints.joinToString(";") { (lng, lat) -> "%.6f,%.6f".format(lng, lat) }
}
