package ca.floo.roadtrip.service.availability

import net.iakovlev.timeshape.TimeZoneEngine
import java.time.ZoneId

private val DEFAULT_TIME_ZONE: ZoneId = ZoneId.of("America/Vancouver")

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
        if (lat == null || lng == null || !lat.isFinite() || !lng.isFinite()) return DEFAULT_TIME_ZONE
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return DEFAULT_TIME_ZONE
        return engine.query(lat, lng).orElse(DEFAULT_TIME_ZONE)
    }
}
