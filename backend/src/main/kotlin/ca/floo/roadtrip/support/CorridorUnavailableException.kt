package ca.floo.roadtrip.support

/** PostGIS could not buffer the route line. Callers see this, never jOOQ's DataAccessException. */
class CorridorUnavailableException(
    cause: Throwable,
) : RuntimeException("route corridor query failed", cause)
