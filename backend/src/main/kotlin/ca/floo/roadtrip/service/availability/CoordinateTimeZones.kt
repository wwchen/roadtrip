package ca.floo.roadtrip.service.availability

import net.iakovlev.timeshape.TimeZoneEngine
import java.time.ZoneId

private val defaultTimeZone: ZoneId = ZoneId.of("America/Vancouver")

internal object CoordinateTimeZones {
    private val engine: TimeZoneEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TimeZoneEngine.initialize()
    }

    fun warmUp() {
        engine.knownZoneIds
    }

    fun resolve(
        lat: Double?,
        lng: Double?,
    ): ZoneId {
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return defaultTimeZone
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return defaultTimeZone
        return engine.query(lat, lng).orElse(defaultTimeZone)
    }
}
