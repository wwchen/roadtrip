package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.CellTransition
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

internal class WatchTransitionDispatch(
    private val liveWatchesCovering: (Collection<Long>) -> List<AvailabilityWatchRepo.Watch>,
    private val dispatch: suspend (List<AvailabilityWatchRepo.Watch>, List<CellTransition>) -> Unit,
    private val scope: CoroutineScope,
) : CellTransitionSink {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun accept(transitions: List<CellTransition>) {
        val bookable = transitions.filter { it.status.isOnlineBookable }
        if (bookable.isEmpty()) return
        scope.launch {
            runCatching {
                val watches = liveWatchesCovering(bookable.map { it.campsiteId }.distinct())
                if (watches.isNotEmpty()) dispatch(watches, bookable)
            }.onFailure { log.warn("reader transition dispatch failed for {} cell(s)", bookable.size, it) }
        }
    }
}
