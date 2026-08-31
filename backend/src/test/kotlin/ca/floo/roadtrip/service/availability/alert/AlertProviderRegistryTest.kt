package ca.floo.roadtrip.service.availability.alert

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.service.availability.WatchStatus
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AlertProviderRegistryTest {
    @Test
    fun `forWatch returns the internal poller by default`() {
        val internal = FakeAlertProvider(AlertProviderRegistry.INTERNAL_POLLER_ID)
        val other = FakeAlertProvider("some_future_vendor")
        val registry = AlertProviderRegistry(listOf(other, internal))

        assertSame(internal, registry.forWatch(fakeWatch(id = 42)))
    }

    @Test
    fun `constructor requires at least one provider`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                AlertProviderRegistry(emptyList())
            }
        assertEquals("AlertProviderRegistry needs at least one AlertProvider", error.message)
    }

    private class FakeAlertProvider(
        override val id: String,
    ) : AlertProvider {
        override val hostsAlerts: Boolean = false

        override fun onWatchActivated(
            scope: WatchAlertScope,
            watch: AvailabilityWatchRepo.Watch,
        ) = Unit

        override fun onWatchDeactivated(
            scope: WatchAlertScope,
            watch: AvailabilityWatchRepo.Watch,
        ) = Unit
    }

    private fun fakeWatch(id: Long): AvailabilityWatchRepo.Watch =
        AvailabilityWatchRepo.Watch(
            id = id,
            ownerUserId = 1L,
            targets = emptyList<AvailabilityWatchTargetRepo.WatchTarget>(),
            campsiteFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = null,
            triggerKinds = emptyList(),
            triggerConfig = JsonObject(emptyMap()),
            stopWhenTriggered = false,
            status = WatchStatus.ACTIVE,
            createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        )
}
