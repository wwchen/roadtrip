package ca.floo.roadtrip.service.health

/**
 * Whether the app can actually serve traffic, as opposed to merely being
 * alive.
 *
 * Liveness (`/api/health`) answers "did Ktor boot and can it reply?" and must
 * stay that boring — a liveness probe that fails on a dependency outage gets
 * the container killed and restarted into the same outage. Readiness is the
 * question a deploy gate and a load balancer actually want answered: are this
 * instance's dependencies reachable right now?
 */
internal fun interface ReadinessService {
    fun report(): Report

    /**
     * The per-dependency verdict. One field today; readiness is the natural
     * home for future dependency probes, and [isReady] is the single place
     * that decides how they combine.
     */
    data class Report(
        val databaseReachable: Boolean,
    ) {
        val isReady: Boolean get() = databaseReachable
    }
}
