package ca.floo.roadtrip.service.availability

import kotlinx.coroutines.CompletableDeferred

internal class DispatchWaiterRegistry {
    private val lock = Any()
    private var nextId = 0L
    private val waiters = LinkedHashMap<Long, Waiter>()

    fun register(selector: DispatchClaimSelector): Registration {
        val deferred = CompletableDeferred<Unit>()
        val id =
            synchronized(lock) {
                nextId += 1
                waiters[nextId] = Waiter(selector = selector, deferred = deferred)
                nextId
            }
        return Registration(id = id, deferred = deferred, onClose = ::unregister)
    }

    fun notifyMatching(dispatch: DispatchQueued): Int {
        val waiter =
            synchronized(lock) {
                val entry =
                    waiters.entries.firstOrNull { (_, waiter) ->
                        waiter.selector.matches(dispatch.kind, dispatch.vendor, dispatch.payloadVersion)
                    } ?: return@synchronized null
                waiters.remove(entry.key)
                entry.value
            } ?: return 0
        waiter.deferred.complete(Unit)
        return 1
    }

    private fun unregister(id: Long) {
        synchronized(lock) {
            waiters.remove(id)
        }
    }

    data class Registration(
        private val id: Long,
        private val deferred: CompletableDeferred<Unit>,
        private val onClose: (Long) -> Unit,
    ) {
        suspend fun await() {
            deferred.await()
        }

        fun close() {
            onClose(id)
        }
    }

    private data class Waiter(
        val selector: DispatchClaimSelector,
        val deferred: CompletableDeferred<Unit>,
    )
}
