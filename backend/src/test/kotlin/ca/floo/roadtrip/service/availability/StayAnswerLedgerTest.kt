package ca.floo.roadtrip.service.availability

import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

private const val LATCH_TIMEOUT_SECONDS = 10L
private const val PARK_TIMEOUT_MS = 10_000L
private const val RACING_POI_COUNT = 64

private const val DONE = "done"

private fun answerOf(index: Int) = "answer-$index"

private fun sweepOf(index: Int) = "sweep-$index"

private fun await(latch: CountDownLatch) {
    if (!latch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) fail("latch never fired")
}

private val parkedOrDone = setOf(Thread.State.WAITING, Thread.State.TERMINATED)

private fun awaitParkedOrDone(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PARK_TIMEOUT_MS)
    while (thread.state !in parkedOrDone) {
        if (System.nanoTime() > deadline) fail("thread never parked or finished: ${thread.state}")
        Thread.onSpinWait()
    }
}

class StayAnswerLedgerTest {
    @Test
    fun `an answer claimed on another thread lands before done, even when its send is preempted`() {
        val ledger = StayAnswerLedger(1)
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val sending = CountDownLatch(1)
        val resume = CountDownLatch(1)

        val read =
            Thread {
                ledger.answer(0) {
                    sending.countDown()
                    await(resume)
                    sent += answerOf(0)
                }
            }
        val sweeper = Thread { ledger.finish(sweep = { sent += sweepOf(it) }, done = { sent += DONE }) }

        read.start()
        await(sending)
        sweeper.start()
        awaitParkedOrDone(sweeper)
        resume.countDown()
        read.join()
        sweeper.join()

        assertEquals(listOf(answerOf(0), DONE), sent.toList())
    }

    @Test
    fun `an index the sweep finds unanswered gets the sweep's answer, and done comes last`() {
        val ledger = StayAnswerLedger(3)
        val sent = mutableListOf<String>()

        ledger.answer(1) { sent += answerOf(1) }
        ledger.finish(sweep = { sent += sweepOf(it) }, done = { sent += DONE })

        assertEquals(listOf(answerOf(1), sweepOf(0), sweepOf(2), DONE), sent)
    }

    @Test
    fun `an index claimed twice sends once`() {
        val ledger = StayAnswerLedger(1)
        val sent = mutableListOf<String>()

        ledger.answer(0) { sent += answerOf(0) }
        ledger.answer(0) { sent += "second" }

        assertEquals(listOf(answerOf(0)), sent)
    }

    @Test
    fun `an index whose send throws is left for the sweep to answer`() {
        val ledger = StayAnswerLedger(1)
        val sent = mutableListOf<String>()

        assertFailsWith<IllegalStateException> { ledger.answer(0) { error("send failed") } }
        ledger.finish(sweep = { sent += sweepOf(it) }, done = { sent += DONE })

        assertEquals(listOf(sweepOf(0), DONE), sent)
    }

    @Test
    fun `a second finish sends nothing, so done stays exactly once`() {
        val ledger = StayAnswerLedger(1)
        val sent = mutableListOf<String>()

        ledger.finish(sweep = { sent += sweepOf(it) }, done = { sent += DONE })
        ledger.finish(sweep = { sent += sweepOf(it) }, done = { sent += DONE })

        assertEquals(listOf(sweepOf(0), DONE), sent)
    }

    @Test
    fun `an answer that arrives after the sweep sends nothing`() {
        val ledger = StayAnswerLedger(1)
        val sent = mutableListOf<String>()

        ledger.finish(sweep = { sent += sweepOf(it) }, done = { sent += DONE })
        ledger.answer(0) { sent += answerOf(0) }

        assertEquals(listOf(sweepOf(0), DONE), sent)
    }

    @Test
    fun `every index is sent exactly once and nothing follows done, with reads racing the sweep`() {
        val ledger = StayAnswerLedger(RACING_POI_COUNT)
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val start = CountDownLatch(1)

        val reads =
            (0 until RACING_POI_COUNT).map { index ->
                Thread {
                    await(start)
                    ledger.answer(index) { sent += answerOf(index) }
                }
            }
        val sweeper =
            Thread {
                await(start)
                ledger.finish(sweep = { sent += sweepOf(it) }, done = { sent += DONE })
            }

        (reads + sweeper).forEach(Thread::start)
        start.countDown()
        (reads + sweeper).forEach(Thread::join)

        val landed = sent.toList()
        assertEquals(RACING_POI_COUNT + 1, landed.size)
        assertEquals(DONE, landed.last())
        val perIndex = IntArray(RACING_POI_COUNT)
        landed.filterNot { it == DONE }.forEach { perIndex[it.substringAfterLast('-').toInt()]++ }
        assertEquals(List(RACING_POI_COUNT) { 1 }, perIndex.toList())
    }
}
