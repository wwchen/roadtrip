package ca.floo.roadtrip.config

data class RouteConfig(
    val maxWaypoints: Int,
    val minCorridorRadiusMiles: Double,
    val maxCorridorRadiusMiles: Double,
) {
    init {
        require(maxWaypoints >= 2) { "route.max-waypoints must be >= 2" }
        require(minCorridorRadiusMiles > 0) { "route.min-corridor-radius-miles must be positive" }
        require(maxCorridorRadiusMiles > minCorridorRadiusMiles) {
            "route.max-corridor-radius-miles must be > min-corridor-radius-miles"
        }
    }

    companion object {
        private const val DEFAULT_MAX_WAYPOINTS = 25
        private const val DEFAULT_MIN_CORRIDOR_RADIUS_MILES = 1.0
        private const val DEFAULT_MAX_CORRIDOR_RADIUS_MILES = 100.0

        fun fromConfig(config: ConfigSection): RouteConfig =
            RouteConfig(
                maxWaypoints = config.value("max-waypoints")?.toInt() ?: DEFAULT_MAX_WAYPOINTS,
                minCorridorRadiusMiles =
                    config.value("min-corridor-radius-miles")?.toDouble()
                        ?: DEFAULT_MIN_CORRIDOR_RADIUS_MILES,
                maxCorridorRadiusMiles =
                    config.value("max-corridor-radius-miles")?.toDouble()
                        ?: DEFAULT_MAX_CORRIDOR_RADIUS_MILES,
            )
    }
}
