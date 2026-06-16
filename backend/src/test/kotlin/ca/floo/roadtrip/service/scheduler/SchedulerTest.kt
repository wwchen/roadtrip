package ca.floo.roadtrip.service.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull

private data class FakeJob(
    override val id: Long,
    override val claimToken: String?,
    val payload: String,
) : Schedulable

private class FakeRepo : SchedulableRepo<FakeJob> {
    val rows = mutableListOf<MutableMap<String, Any?>>()
    val released = mutableListOf<Triple<Long, OffsetDateTime, OffsetDateTime>>()

    fun add(
        id: Long,
        nextRunAt: OffsetDateTime,
        payload: String,
    ) {
        rows +=
            mutableMapOf(
                "id" to id,
                "claim_token" to null,
                "claimed_until" to null,
                "next_run_at" to nextRunAt,
                "payload" to payload,
            )
    }

    override fun claimDue(
        now: OffsetDateTime,
        limit: Int,
        leaseDuration: Duration,
    ): List<FakeJob> {
        val claimable =
            rows
                .filter {
                    val due = it["next_run_at"] as OffsetDateTime
                    val lease = it["claimed_until"] as OffsetDateTime?
                    due <= now && (lease == null || lease < now)
                }.take(limit)
        val token = "tok-${now.toEpochSecond()}"
        for (row in claimable) {
            row["claim_token"] = token
            row["claimed_until"] = now.plus(leaseDuration)
        }
        return claimable.map { FakeJob(it["id"] as Long, it["claim_token"] as String, it["payload"] as String) }
    }

    override fun release(
        id: Long,
        token: String,
        nextRunAt: OffsetDateTime,
        ranAt: OffsetDateTime,
    ): Boolean {
        val row = rows.first { it["id"] == id }
        if (row["claim_token"] != token) return false
        row["claim_token"] = null
        row["claimed_until"] = null
        row["next_run_at"] = nextRunAt
        released += Triple(id, ranAt, nextRunAt)
        return true
    }

    override fun reclaimExpired(now: OffsetDateTime): Int {
        var count = 0
        for (row in rows) {
            val lease = row["claimed_until"] as OffsetDateTime?
            if (lease != null && lease < now) {
                row["claim_token"] = null
                row["claimed_until"] = null
                count += 1
            }
        }
        return count
    }
}

class SchedulerTest {
    @Test
    fun `due rows get handed to the handler`() =
        runBlocking {
            val repo = FakeRepo()
            val ranIds = mutableListOf<Long>()
            repo.add(1L, OffsetDateTime.now().minusSeconds(10), "a")
            repo.add(2L, OffsetDateTime.now().minusSeconds(10), "b")
            val done = CompletableDeferred<Unit>()
            val handler: suspend (FakeJob) -> HandlerResult = { row ->
                ranIds += row.id
                if (ranIds.size == 2) done.complete(Unit)
                HandlerResult(nextRunAt = OffsetDateTime.now().plusMinutes(1))
            }
            val scheduler =
                Scheduler(
                    repo = repo,
                    handler = handler,
                    tickInterval = Duration.ofMillis(20),
                    claimBatchSize = 5,
                    leaseDuration = Duration.ofSeconds(30),
                )
            coroutineScope {
                scheduler.start(this)
                withTimeout(2_000) { done.await() }
                scheduler.stop()
            }
            assertEquals(setOf(1L, 2L), ranIds.toSet())
            assertEquals(2, repo.released.size)
        }

    @Test
    fun `handler exception still releases the row`() =
        runBlocking {
            val repo = FakeRepo()
            repo.add(1L, OffsetDateTime.now().minusSeconds(10), "a")
            val attempts = AtomicInteger(0)
            val seen = AtomicReference<HandlerResult?>(null)
            val done = CompletableDeferred<Unit>()
            val handler: suspend (FakeJob) -> HandlerResult = {
                if (attempts.incrementAndGet() == 1) {
                    done.complete(Unit)
                    throw RuntimeException("boom")
                }
                HandlerResult(OffsetDateTime.now().plusMinutes(5)).also { seen.set(it) }
            }
            val scheduler =
                Scheduler(
                    repo = repo,
                    handler = handler,
                    tickInterval = Duration.ofMillis(20),
                    claimBatchSize = 1,
                    leaseDuration = Duration.ofSeconds(30),
                )
            coroutineScope {
                scheduler.start(this)
                withTimeout(2_000) { done.await() }
                // Give the release a moment to finish writing.
                delay(100)
                scheduler.stop()
            }
            assertEquals(1, repo.released.size)
            // Released even though the handler threw.
            assertNull(seen.get())
        }

    @Test
    fun `boot recovery clears expired leases`() {
        val repo = FakeRepo()
        repo.add(1L, OffsetDateTime.now().minusMinutes(5), "a")
        // Pretend a previous run claimed the row and crashed.
        repo.rows[0]["claim_token"] = "stale"
        repo.rows[0]["claimed_until"] = OffsetDateTime.now().minusSeconds(1)
        runBlocking {
            val scheduler =
                Scheduler(
                    repo = repo,
                    handler = { HandlerResult(OffsetDateTime.now().plusMinutes(1)) },
                    tickInterval = Duration.ofSeconds(60),
                    claimBatchSize = 1,
                    leaseDuration = Duration.ofSeconds(30),
                )
            coroutineScope {
                scheduler.start(this)
                delay(50)
                scheduler.stop()
            }
        }
        assertNull(repo.rows[0]["claim_token"])
    }
}
