package ca.floo.roadtrip.model.api.poi

import ca.floo.roadtrip.model.domain.CarrierSignal
import kotlinx.serialization.Serializable

/** One cell-coverage reading as the API serves it; the carrier's display name rides along. */
@Serializable
data class CarrierSignalDto(
    val carrier: String,
    val label: String,
    val average: Double,
    val count: Int? = null,
) {
    companion object {
        fun from(signal: CarrierSignal): CarrierSignalDto =
            CarrierSignalDto(
                carrier = signal.carrier.wire,
                label = signal.carrier.label,
                average = signal.average,
                count = signal.count,
            )
    }
}
