package ca.floo.roadtrip.service.availability

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class StayAnswerLedger(
    poiCount: Int,
) {
    private val lock = ReentrantLock()
    private val answered = BooleanArray(poiCount)

    fun answer(
        index: Int,
        send: () -> Unit,
    ) = lock.withLock {
        if (!answered[index]) {
            answered[index] = true
            send()
        }
    }

    fun finish(
        sweep: (Int) -> Unit,
        done: () -> Unit,
    ) = lock.withLock {
        answered.indices.forEach { index ->
            if (!answered[index]) {
                answered[index] = true
                sweep(index)
            }
        }
        done()
    }
}
