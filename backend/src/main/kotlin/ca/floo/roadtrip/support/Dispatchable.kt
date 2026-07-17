package ca.floo.roadtrip.support

interface Dispatchable<K> {
    fun canHandle(key: K): Boolean
}

fun <K, T : Dispatchable<K>> List<T>.firstHandlerFor(key: K): T? = firstOrNull { it.canHandle(key) }

fun <K, T : Dispatchable<K>> List<T>.allHandlersFor(key: K): List<T> = filter { it.canHandle(key) }
