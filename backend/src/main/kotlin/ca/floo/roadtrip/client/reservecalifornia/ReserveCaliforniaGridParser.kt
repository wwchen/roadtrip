package ca.floo.roadtrip.client.reservecalifornia

import ca.floo.roadtrip.client.AvailabilityPayloadReads
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate

private const val NO_RESERVATION_ID = 0L

object ReserveCaliforniaGridParser {
    private val reads = AvailabilityPayloadReads("reservecalifornia")

    fun parse(
        payload: JsonObject,
        observedAt: Instant,
    ): ReserveCaliforniaGridAvailability {
        val facility = reads.obj(payload["Facility"], "Facility")
        val facilityId = reads.long(facility["FacilityId"], "Facility.FacilityId")
        val units = reads.obj(facility["Units"], "Facility.Units")
        val statuses = linkedMapOf<String, Map<LocalDate, AvailabilityStatus>>()
        val names = linkedMapOf<String, String?>()

        for ((unitKey, rawUnit) in units) {
            val unit = reads.obj(rawUnit, "unit $unitKey")
            val unitId = reads.long(unit["UnitId"], "unit $unitKey UnitId").toString()
            names[unitId] = unit["Name"]?.jsonPrimitive?.contentOrNull
            val isWebViewable = reads.boolean(unit["IsWebViewable"], "unit $unitId IsWebViewable")
            val allowWebBooking = reads.boolean(unit["AllowWebBooking"], "unit $unitId AllowWebBooking")
            val slices = reads.obj(unit["Slices"], "unit $unitId Slices")
            val byDate = linkedMapOf<LocalDate, AvailabilityStatus>()
            for ((sliceKey, rawSlice) in slices) {
                val slice = reads.obj(rawSlice, "unit $unitId slice $sliceKey")
                val rawDate =
                    slice["Date"]
                        ?.takeUnless { it is JsonNull }
                        ?.let { reads.string(it, "unit $unitId slice $sliceKey Date") }
                        ?: sliceKey
                val date = reads.isoDateOfTimestamp(rawDate, "unit $unitId slice date")
                val reservationId = reads.long(slice["ReservationId"], "unit $unitId slice $sliceKey ReservationId")
                byDate[date] =
                    classify(
                        slice,
                        reservationId = reservationId,
                        isWebViewable = isWebViewable,
                        allowWebBooking = allowWebBooking,
                    )
            }
            statuses[unitId] = byDate
        }

        return ReserveCaliforniaGridAvailability(
            facilityId = facilityId,
            observedAt = observedAt,
            statuses = statuses,
            unitNames = names,
        )
    }

    private fun classify(
        slice: JsonObject,
        reservationId: Long,
        isWebViewable: Boolean,
        allowWebBooking: Boolean,
    ): AvailabilityStatus {
        if (!isWebViewable || !allowWebBooking) return AvailabilityStatus.CLOSED
        val isWalkin = slice["IsWalkin"]?.jsonPrimitive?.booleanOrNull == true
        if (isWalkin) return AvailabilityStatus.FIRST_COME
        val isBlocked = slice["IsBlocked"]?.jsonPrimitive?.booleanOrNull == true
        if (isBlocked) return AvailabilityStatus.CLOSED
        if (reservationId != NO_RESERVATION_ID) return AvailabilityStatus.RESERVED
        val isFree = slice["IsFree"]?.jsonPrimitive?.booleanOrNull == true
        val lock = slice["Lock"]
        return if (isFree && (lock == null || lock == JsonNull)) AvailabilityStatus.AVAILABLE else AvailabilityStatus.UNKNOWN
    }
}
