package ca.floo.roadtrip.service.availability

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class StayAnswerLedger(
    poiCount: Int,
) {
    private val lock = ReentrantLock()
    private val answered = BooleanArray(poiCount)
    private var finished = false

    fun answer(
        index: Int,
        send: () -> Unit,
    ) = lock.withLock {
        if (!finished && !answered[index]) {
            send()
            answered[index] = true
        }
    }

    fun finish(
        sweep: (Int) -> Unit,
        done: () -> Unit,
    ) = lock.withLock {
        if (!finished) {
            answered.indices.forEach { index ->
                if (!answered[index]) {
                    sweep(index)
                    answered[index] = true
                }
            }
            done()
            finished = true
        }
    }
}
