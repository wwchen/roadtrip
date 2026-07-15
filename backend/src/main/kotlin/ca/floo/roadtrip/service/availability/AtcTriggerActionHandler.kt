package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.service.notification.WatchOpening
import org.slf4j.LoggerFactory

internal class AtcTriggerActionHandler(
    private val dispatches: AtcDispatchPort,
) : TriggerActionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val kind: String = KIND

    override suspend fun fire(
        watch: AvailabilityWatchRepo.Watch,
        openings: List<WatchOpening>,
    ): Boolean {
        runCatching { dispatches.enqueueAtc(watch, openings) }
            .onFailure { log.error("failed to enqueue ATC dispatch for watch {}", watch.id, it) }
        return false
    }

    companion object {
        const val KIND = "atc"
    }
}
