package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.ReservableAvailabilityLog.Companion.RESERVABLE_AVAILABILITY_LOG
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

class ReservableAvailabilityLogRepo(
    private val ctx: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) {
    data class AvailabilityPoll(
        val reservableRid: String,
        val response: AvailabilityResponseDto,
    )

    fun appendAvailabilityPoll(input: AvailabilityPoll): Int {
        require(input.reservableRid.isNotBlank()) { "reservableRid must not be blank" }

        if (input.response.availability.isEmpty()) return 0

        val observedAt = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val inserts =
            input.response.availability.map { day ->
                ctx
                    .insertInto(RESERVABLE_AVAILABILITY_LOG)
                    .set(RESERVABLE_AVAILABILITY_LOG.RESERVABLE_RID, input.reservableRid)
                    .set(RESERVABLE_AVAILABILITY_LOG.OBSERVED_AT, observedAt)
                    .set(RESERVABLE_AVAILABILITY_LOG.TARGET_DATE, LocalDate.parse(day.date))
                    .set(RESERVABLE_AVAILABILITY_LOG.STATUS, day.status)
                    .set(RESERVABLE_AVAILABILITY_LOG.AVAILABLE, day.availableCount > 0)
                    .set(RESERVABLE_AVAILABILITY_LOG.AVAILABLE_COUNT, day.availableCount)
                    .set(RESERVABLE_AVAILABILITY_LOG.TOTAL, day.total)
                    .set(RESERVABLE_AVAILABILITY_LOG.DAY_PAYLOAD, JSONB.valueOf(day.toJson()))
            }
        ctx.batch(inserts).execute()
        return inserts.size
    }

    private fun AvailabilityDayDto.toJson(): String = availabilityResponseJson.encodeToString(AvailabilityDayDto.serializer(), this)
}
