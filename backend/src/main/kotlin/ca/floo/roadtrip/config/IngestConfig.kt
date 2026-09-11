package ca.floo.roadtrip.config

import java.time.Duration

private const val STALE_RUN_AFTER_KEY = "stale-run-after"

private val defaultStaleRunAfter: Duration = Duration.ofMinutes(30)

/**
 * Ingest-run lifecycle settings.
 *
 * [staleRunAfter] is how long a parent `ingest_runs` row may sit `started`
 * before the boot sweep marks it aborted — a restart mid-run leaves them
 * behind, because the controller coroutine that owned them is gone. The
 * default is well beyond the longest expected phase (the rec.gov enricher
 * tops out near 10 min today); a bigger catalog or a slower machine moves it.
 */
data class IngestConfig(
    val staleRunAfter: Duration,
) {
    companion object {
        fun fromConfig(config: ConfigSection): IngestConfig =
            IngestConfig(staleRunAfter = config.duration(STALE_RUN_AFTER_KEY, defaultStaleRunAfter))
    }
}
