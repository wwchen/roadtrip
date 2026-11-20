package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.observability.RoadtripMetrics
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

val unrecognizedStatusLogInterval: Duration = Duration.ofHours(1)

private const val UNRECOGNIZED_STATUS_LOG_KEYS_MAX = 1_000

class UnrecognizedStatusReporter(
    private val metrics: RoadtripMetrics,
    private val clock: () -> Instant = Instant::now,
    private val log: Logger = LoggerFactory.getLogger(UnrecognizedStatusReporter::class.java),
) {
    private val lastLoggedAt = ConcurrentHashMap<Pair<BookingProvider, String>, Instant>()

    fun report(
        provider: BookingProvider,
        counts: Map<String, Int>,
    ) {
        if (counts.isEmpty()) return
        metrics.availabilityStatusUnrecognized(provider, counts.values.sum())
        val now = clock()
        val due = counts.filterKeys { raw -> claimLog(provider to raw, now) }
        if (due.isNotEmpty()) {
            log.warn("{} sent availability statuses with no classification, shown as unknown: {}", provider.id, due)
        }
    }

    private fun claimLog(
        key: Pair<BookingProvider, String>,
        now: Instant,
    ): Boolean {
        if (lastLoggedAt.size >= UNRECOGNIZED_STATUS_LOG_KEYS_MAX) {
            lastLoggedAt.entries.removeIf { (_, at) -> !now.isBefore(at.plus(unrecognizedStatusLogInterval)) }
        }
        var claimed = false
        lastLoggedAt.compute(key) { _, at ->
            if (at == null || !now.isBefore(at.plus(unrecognizedStatusLogInterval))) {
                claimed = true
                now
            } else {
                at
            }
        }
        return claimed
    }
}
