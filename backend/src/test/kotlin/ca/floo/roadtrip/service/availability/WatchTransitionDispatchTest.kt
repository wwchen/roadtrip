package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CellTransition
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

private val night: LocalDate = LocalDate.parse("2026-12-01")
private val recordedAt: Instant = Instant.parse("2026-11-07T12:00:00Z")
private val clock: Clock = Clock.fixed(recordedAt, ZoneOffset.UTC)
private val opened = CellTransition(campsiteId = 7, targetDate = night, status = AvailabilityStatus.AVAILABLE)

class WatchTransitionDispatchTest {
    @Test
    fun `transitions reach the dispatcher with the time they were recorded`() {
        val dispatched = mutableListOf<Pair<List<CellTransition>, Instant>>()
        runBlocking {
            WatchTransitionDispatch(
                dispatchCovering = { transitions, at -> dispatched += transitions to at },
                scope = this,
                clock = clock,
            ).accept(listOf(opened))
        }
        assertEquals(listOf(listOf(opened) to recordedAt), dispatched)
    }

    @Test
    fun `no transitions dispatch nothing`() {
        runBlocking {
            WatchTransitionDispatch(
                dispatchCovering = { _, _ -> error("nothing to dispatch") },
                scope = this,
                clock = clock,
            ).accept(emptyList())
        }
    }

    @Test
    fun `a failing dispatch is swallowed`() {
        runBlocking {
            WatchTransitionDispatch(
                dispatchCovering = { _, _ -> error("database down") },
                scope = this,
                clock = clock,
            ).accept(listOf(opened))
        }
    }
}
