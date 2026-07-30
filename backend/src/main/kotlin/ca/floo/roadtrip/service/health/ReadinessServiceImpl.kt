package ca.floo.roadtrip.service.health

import ca.floo.roadtrip.repo.DatabaseHealthRepo
import org.slf4j.LoggerFactory

/**
 * Probes each dependency and folds the results into a [ReadinessService.Report].
 *
 * A probe failure is the answer, not an error: any throw from the repo — pool
 * exhausted, server down, query timed out — means "not reachable". It is logged
 * at WARN rather than swallowed, because a flapping readiness probe with no
 * trace of *why* is the failure mode this endpoint exists to prevent.
 */
internal class ReadinessServiceImpl(
    private val databaseHealthRepo: DatabaseHealthRepo,
) : ReadinessService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun report(): ReadinessService.Report = ReadinessService.Report(databaseReachable = probeDatabase())

    private fun probeDatabase(): Boolean =
        runCatching { databaseHealthRepo.isReachable() }
            .getOrElse { failure ->
                log.warn("Readiness probe: database is not reachable", failure)
                false
            }
}
