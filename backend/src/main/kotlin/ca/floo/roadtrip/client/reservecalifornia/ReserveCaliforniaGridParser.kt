package ca.floo.roadtrip.client.reservecalifornia

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.reservecalifornia.ReserveCaliforniaGridAvailability
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate

object ReserveCaliforniaGridParser {
    fun parse(
        payload: JsonObject,
        observedAt: Instant,
    ): ReserveCaliforniaGridAvailability {
        val facility = payload["Facility"]?.jsonObject ?: JsonObject(emptyMap())
        val facilityId = facility["FacilityId"]?.jsonPrimitive?.longOrNull ?: 0L
        val units = facility["Units"]?.jsonObject ?: JsonObject(emptyMap())
        val statuses = linkedMapOf<String, Map<LocalDate, AvailabilityStatus>>()
        val names = linkedMapOf<String, String?>()

        for ((_, rawUnit) in units) {
            val unit = rawUnit as? JsonObject ?: continue
            val unitId = unit["UnitId"]?.jsonPrimitive?.longOrNull?.toString() ?: continue
            names[unitId] = unit["Name"]?.jsonPrimitive?.contentOrNull
            val isWebViewable = unit["IsWebViewable"]?.jsonPrimitive?.booleanOrNull ?: false
            val allowWebBooking = unit["AllowWebBooking"]?.jsonPrimitive?.booleanOrNull ?: false
            val slices = unit["Slices"]?.jsonObject ?: JsonObject(emptyMap())
            val byDate = linkedMapOf<LocalDate, AvailabilityStatus>()
            for ((sliceKey, rawSlice) in slices) {
                val slice = rawSlice as? JsonObject ?: continue
                val date =
                    slice["Date"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.take(10)
                        ?.let(LocalDate::parse)
                        ?: sliceKey.take(10).let(LocalDate::parse)
                byDate[date] = classify(slice, isWebViewable = isWebViewable, allowWebBooking = allowWebBooking)
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
        isWebViewable: Boolean,
        allowWebBooking: Boolean,
    ): AvailabilityStatus {
        if (!isWebViewable || !allowWebBooking) return AvailabilityStatus.CLOSED
        val isWalkin = slice["IsWalkin"]?.jsonPrimitive?.booleanOrNull == true
        if (isWalkin) return AvailabilityStatus.FIRST_COME
        val isBlocked = slice["IsBlocked"]?.jsonPrimitive?.booleanOrNull == true
        if (isBlocked) return AvailabilityStatus.CLOSED
        val reservationId = slice["ReservationId"]?.jsonPrimitive?.longOrNull ?: 0L
        if (reservationId != 0L) return AvailabilityStatus.RESERVED
        val isFree = slice["IsFree"]?.jsonPrimitive?.booleanOrNull == true
        val lock = slice["Lock"]
        return if (isFree && (lock == null || lock == JsonNull)) AvailabilityStatus.AVAILABLE else AvailabilityStatus.UNKNOWN
    }
}
