package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CellTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

internal class WatchTransitionDispatch(
    private val dispatchCovering: suspend (List<CellTransition>, Instant) -> Unit,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) : CellTransitionSink {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun accept(transitions: List<CellTransition>) {
        if (transitions.isEmpty()) return
        val recordedAt = clock.instant()
        scope.launch {
            runCatching { dispatchCovering(transitions, recordedAt) }
                .onFailure { log.warn("reader transition dispatch failed for {} cell(s)", transitions.size, it) }
        }
    }
}
