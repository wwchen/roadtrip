package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.ReservableAvailabilityMonitorLog.Companion.RESERVABLE_AVAILABILITY_MONITOR_LOG
import ca.floo.roadtrip.service.api.AvailabilityDayDto
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.availabilityResponseJson
import kotlinx.serialization.encodeToString
import org.jooq.DSLContext
import org.jooq.JSONB
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class ReservableAvailabilityMonitorLogRepo(
    private val ctx: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class AvailabilityPoll(
        val monitorId: Long? = null,
        val reservableRid: String,
        val provider: String,
        val response: AvailabilityResponseDto,
        val windowStart: LocalDate,
        val windowDays: Int,
        val minNights: Int,
        val force: Boolean,
    )

    fun appendAvailabilityPoll(input: AvailabilityPoll): Int {
        require(input.reservableRid.isNotBlank()) { "reservableRid must not be blank" }
        require(input.provider.isNotBlank()) { "provider must not be blank" }
        require(input.windowDays > 0) { "windowDays must be positive" }
        require(input.minNights >= 1) { "minNights must be at least 1" }

        if (input.response.availability.isEmpty()) return 0

        val observedAt = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val inserts =
            input.response.availability.map { day ->
                ctx
                    .insertInto(RESERVABLE_AVAILABILITY_MONITOR_LOG)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.MONITOR_ID, input.monitorId)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.RESERVABLE_RID, input.reservableRid)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.PROVIDER, input.provider)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.OBSERVED_AT, observedAt)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.TARGET_DATE, LocalDate.parse(day.date))
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.WINDOW_START, input.windowStart)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.WINDOW_DAYS, input.windowDays)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.MIN_NIGHTS, input.minNights)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.FORCE, input.force)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.CACHE_HIT, input.response.cache.hit)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.STATUS, day.status)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.AVAILABLE, day.availableCount > 0)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.AVAILABLE_COUNT, day.availableCount)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.TOTAL, day.total)
                    .set(RESERVABLE_AVAILABILITY_MONITOR_LOG.DAY_PAYLOAD, JSONB.valueOf(day.toJson()))
            }
        ctx.batch(inserts).execute()
        return inserts.size
    }

    private fun AvailabilityDayDto.toJson(): String = availabilityResponseJson.encodeToString(AvailabilityDayDto.serializer(), this)
}
