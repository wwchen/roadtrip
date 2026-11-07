package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CellTransition
import ca.floo.roadtrip.model.availability.WatchStatus
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals

private val night: LocalDate = LocalDate.parse("2026-12-01")
private val opened = CellTransition(campsiteId = 7, targetDate = night, status = AvailabilityStatus.AVAILABLE)
private val booked = CellTransition(campsiteId = 8, targetDate = night, status = AvailabilityStatus.RESERVED)

private fun watch(id: Long) =
    AvailabilityWatchRepo.Watch(
        id = id,
        ownerUserId = 1,
        targets = emptyList(),
        campsiteFilters = JsonObject(emptyMap()),
        startDate = night,
        endDate = night.plusDays(1),
        cadenceSec = null,
        triggerKinds = listOf("email_notify"),
        triggerConfig = JsonObject(emptyMap()),
        stopWhenTriggered = true,
        status = WatchStatus.ACTIVE,
        doneReason = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )

class WatchTransitionDispatchTest {
    @Test
    fun `bookable transitions reach the dispatcher with the watches covering their campsites`() {
        val looked = mutableListOf<Collection<Long>>()
        val dispatched = mutableListOf<Pair<List<Long>, List<CellTransition>>>()
        runBlocking {
            WatchTransitionDispatch(
                liveWatchesCovering = { ids ->
                    looked += ids
                    listOf(watch(41))
                },
                dispatch = { watches, transitions -> dispatched += watches.map { it.id } to transitions },
                scope = this,
            ).accept(listOf(opened, booked))
        }
        assertEquals(listOf<Collection<Long>>(listOf(7L)), looked)
        assertEquals(listOf(listOf(41L) to listOf(opened)), dispatched)
    }

    @Test
    fun `transitions with nothing bookable look nothing up`() {
        var lookups = 0
        runBlocking {
            WatchTransitionDispatch(
                liveWatchesCovering = {
                    lookups++
                    emptyList()
                },
                dispatch = { _, _ -> error("nothing bookable must not dispatch") },
                scope = this,
            ).accept(listOf(booked))
        }
        assertEquals(0, lookups)
    }

    @Test
    fun `no live watch means no dispatch, and a failing lookup is swallowed`() {
        runBlocking {
            WatchTransitionDispatch(
                liveWatchesCovering = { emptyList() },
                dispatch = { _, _ -> error("no watch must not dispatch") },
                scope = this,
            ).accept(listOf(opened))
            WatchTransitionDispatch(
                liveWatchesCovering = { error("database down") },
                dispatch = { _, _ -> error("unreachable") },
                scope = this,
            ).accept(listOf(opened))
        }
    }
}
