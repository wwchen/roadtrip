package ca.floo.roadtrip.service.etl.vendors.osmpf

import kotlinx.serialization.Serializable
import java.time.Instant

// DTO mirroring Overpass's response shape. `fetchedAt` is set by the ETL
// after deserialization (it isn't on the wire — comes from the envelope).
@Serializable
data class PlanetFitnessRawDto(
    val elements: List<OverpassElement> = emptyList(),
    @kotlinx.serialization.Transient val fetchedAt: Instant = Instant.EPOCH,
)
