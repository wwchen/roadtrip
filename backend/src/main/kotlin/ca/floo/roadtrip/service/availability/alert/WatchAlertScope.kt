package ca.floo.roadtrip.service.availability.alert

import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import org.jooq.DSLContext

/**
 * The watch-write transaction as an alert provider sees it: repo handles already
 * bound to it, and no jOOQ type on the port. A vendor-hosted provider that only
 * calls an upstream API can ignore the whole scope.
 */
internal interface WatchAlertScope {
    /** Poller bookkeeping for this transaction. Never opens a connection of its own. */
    val pollerRepo: AvailabilityPollerRepo
}

internal class TransactionalWatchAlertScope(
    txn: DSLContext,
) : WatchAlertScope {
    override val pollerRepo: AvailabilityPollerRepo by lazy { AvailabilityPollerRepo(txn) }
}
