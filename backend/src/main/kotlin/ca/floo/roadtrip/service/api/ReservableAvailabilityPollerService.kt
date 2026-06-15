package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.ReservableAvailabilityScopeSchema
import ca.floo.roadtrip.repo.ReservableAvailabilityPollerRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Duration

internal class ReservableAvailabilityPollerService(
    private val pollers: ReservableAvailabilityPollerRepo,
    private val intents: ReservableAvailabilityIntentService,
    private val scope: CoroutineScope,
    private val interval: Duration = Duration.ofSeconds(5),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun start() {
        scope.launch {
            while (isActive) {
                try {
                    pollDueOnce()
                } catch (e: Exception) {
                    log.warn("reservable availability poller sweep failed: {}", e.message)
                }
                delay(interval.toMillis())
            }
        }
    }

    suspend fun pollDueOnce(limit: Int = 10): Int {
        val claimed = pollers.claimDue(limit)
        for (claim in claimed) {
            val poller = claim.poller
            try {
                val query =
                    intents.pollerIntent(
                        scope = poller.scope.toApiScope(),
                        filters = intents.filtersFromJson(poller.reservableFilters),
                        targetDates = poller.targetDates,
                        minNights = poller.minNights,
                    )
                val execution = intents.execute(query, sourceKind = "poller", pollerId = poller.id)
                val targetDates = poller.targetDates.map { it.toString() }.toSet()
                val triggered =
                    execution.response.results.any { result ->
                        result.matchingStarts.any { it in targetDates }
                    }
                pollers.completeClaim(
                    id = poller.id,
                    claimToken = claim.claimToken,
                    triggered = triggered,
                    stopWhenTriggered = poller.stopWhenTriggered,
                )
            } catch (e: Exception) {
                log.warn("reservable availability poller id={} failed: {}", poller.id, e.message)
                pollers.failClaim(poller.id, claim.claimToken)
            }
        }
        return claimed.size
    }

    private fun ReservableAvailabilityPollerRepo.Scope.toApiScope(): ReservableAvailabilityScopeSchema =
        poiId
            ?.let { ReservableAvailabilityScopeSchema(poiId = it) }
            ?: ReservableAvailabilityScopeSchema(rid = requireNotNull(reservableRid).encode())
}
