package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchInitialNotificationPolicyTest {
    @Test
    fun `config-only update does not dispatch initial notification`() {
        val before = watch(triggerKinds = listOf("slack_notify"))
        val after =
            before.copy(
                triggerConfig =
                    JsonObject(
                        mapOf(
                            "slack_notify" to JsonObject(mapOf("channel" to JsonPrimitive("#camping"))),
                        ),
                    ),
            )

        assertFalse(WatchInitialNotificationPolicy.shouldDispatchAfterUpdate(before, after))
    }

    @Test
    fun `new trigger kind dispatches initial notification`() {
        val before = watch(triggerKinds = listOf("slack_notify"))
        val after = before.copy(triggerKinds = listOf("slack_notify", "atc"))

        assertTrue(WatchInitialNotificationPolicy.shouldDispatchAfterUpdate(before, after))
    }

    @Test
    fun `inactive watch does not dispatch initial notification`() {
        val before = watch(triggerKinds = listOf("slack_notify"))
        val after = before.copy(status = WatchStatus.PAUSED, startDate = before.startDate.plusDays(1))

        assertFalse(WatchInitialNotificationPolicy.shouldDispatchAfterUpdate(before, after))
    }

    private fun watch(triggerKinds: List<String>): AvailabilityWatchRepo.Watch =
        AvailabilityWatchRepo.Watch(
            id = 42L,
            targets = listOf(AvailabilityWatchTargetRepo.WatchTarget(id = 1L, watchId = 42L, poiId = 99L, campsiteId = null)),
            campsiteFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-05"),
            cadenceSec = 60,
            triggerKinds = triggerKinds,
            triggerConfig = JsonObject(emptyMap()),
            stopWhenTriggered = true,
            status = WatchStatus.ACTIVE,
            createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        )
}
