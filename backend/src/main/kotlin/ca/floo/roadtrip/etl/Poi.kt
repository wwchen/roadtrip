package ca.floo.roadtrip.etl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.LocalDate

// Sealed Poi hierarchy per RFC 0007 Section 4. Per-type properties named
// once and referenced thereafter; display-rule logic in
// buildDrawerPayload (PR 5) becomes exhaustive over the sealed type.
//
// The DB row shape (V5 pois) is the union of all variants' fields plus
// JSONB `properties` for per-type extras the columns don't promote.
sealed class Poi {
    abstract val source: String
    abstract val sourceId: String
    abstract val name: String

    // GeoJSON geometry as a string. Stays opaque through the ETL and
    // gets handed to ST_GeomFromGeoJSON at upsert time. Avoids dragging
    // PostGIS bindings or JTS into the ETL types.
    abstract val geomGeoJson: String
    abstract val region: String?
    abstract val country: String?
    abstract val governingBodyId: Long
    abstract val phone: String?
    abstract val address: Address?
    abstract val infoUrl: String?
    abstract val fetchedAt: Instant
    abstract val lastVerified: LocalDate?

    // ----- per-type variants -----

    data class Campground(
        override val source: String,
        override val sourceId: String,
        override val name: String,
        override val geomGeoJson: String,
        override val region: String?,
        override val country: String?,
        override val governingBodyId: Long,
        override val phone: String?,
        override val address: Address?,
        override val infoUrl: String?,
        override val fetchedAt: Instant,
        override val lastVerified: LocalDate?,
        val providerRef: ProviderRef?,
        val amenities: List<String>,
        val activities: List<String>,
        val sites: Int?,
        val season: String?,
        val near: String?,
        val photoUrl: String?,
        val cellCoverage: Map<String, CellSignal>?,
        val ratingReviews: RatingSummary?,
    ) : Poi()

    data class Supercharger(
        override val source: String,
        override val sourceId: String,
        override val name: String,
        override val geomGeoJson: String,
        override val region: String?,
        override val country: String?,
        override val governingBodyId: Long,
        override val phone: String?,
        override val address: Address?,
        override val infoUrl: String?,
        override val fetchedAt: Instant,
        override val lastVerified: LocalDate?,
        val stallCount: Int,
        val maxPowerKw: Int,
        val facility: String?,
        // Tesla "effective pricebooks": one row per (feeType, vehicleMakeType,
        // currency, time-band). Whole array kept verbatim for the FE to
        // render. Empty list when the detail capture is missing or had no
        // pricebooks.
        val pricebooks: List<kotlinx.serialization.json.JsonElement> = emptyList(),
    ) : Poi()

    data class Park(
        override val source: String,
        override val sourceId: String,
        override val name: String,
        override val geomGeoJson: String,
        override val region: String?,
        override val country: String?,
        override val governingBodyId: Long,
        override val phone: String?,
        override val address: Address?,
        override val infoUrl: String?,
        override val fetchedAt: Instant,
        override val lastVerified: LocalDate?,
        val parkType: ParkType,
        val designation: String,
        val officialName: String?,
        val acres: Double?,
    ) : Poi()

    data class PlanetFitness(
        override val source: String,
        override val sourceId: String,
        override val name: String,
        override val geomGeoJson: String,
        override val region: String?,
        override val country: String?,
        override val governingBodyId: Long,
        override val phone: String?,
        override val address: Address?,
        override val infoUrl: String?,
        override val fetchedAt: Instant,
        override val lastVerified: LocalDate?,
        val openingHours: String?,
    ) : Poi()
}

// Value types referenced by Poi variants. Kept here to keep the per-type
// data classes from sprawling across files; if any of these grow their
// own logic (validators, parsers), promote them.

data class Address(
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val postcode: String? = null,
    val country: String? = null,
)

data class CellSignal(
    val avg: Float, // 0..4 on rec.gov's scale
    val count: Int,
)

data class RatingSummary(
    val avg: Float, // 0..5
    val count: Int,
)

enum class ParkType {
    NATIONAL,
    STATE,
    PROVINCIAL,
}

// Sealed ProviderRef per RFC decision #22. Stored as JSONB on the row
// (payload only — booking_provider_id FK is the discriminator per
// decision #24); deserialized into one of these variants by the
// matching adapter.
sealed class ProviderRef {
    data class RecGov(
        val recgovId: String,
    ) : ProviderRef()

    // Aspira NextGen: same adapter, different host per booking_provider row
    // (PC/BC/WA). Host comes from booking_provider, not duplicated here
    // (RFC decision #23).
    data class Aspira(
        val transactionLocationId: Long,
        val mapId: Long,
        val resourceLocationId: Long?,
    ) : ProviderRef()

    // Camis (Alberta Parks). No adapter implemented yet (RFC decision #11);
    // the ProviderRef variant exists so the type system stays exhaustive.
    data class Camis(
        val facilityId: String,
    ) : ProviderRef()
}

// Drives the row's `category` column. Stable strings that match the
// V5 CHECK constraint.
fun Poi.categorySql(): String =
    when (this) {
        is Poi.Campground -> "campground"
        is Poi.Supercharger -> "supercharger"
        is Poi.Park ->
            when (this.parkType) {
                ParkType.NATIONAL -> "national-park"
                ParkType.STATE, ParkType.PROVINCIAL -> "state-park"
            }
        is Poi.PlanetFitness -> "planet-fitness"
    }

// Per-type extras that don't fit the promoted columns. The ETL serializes
// this into the row's JSONB `properties` column; PR 5's buildDrawerPayload
// reads it back when needed.
//
// Today's column promotions cover the 80% case; this catches the long
// tail (rec.gov enrichment, Tesla-specific fields the FE may want later,
// PAD-US extras like Pub_Access). Per decision #20, this stays JSONB —
// promoting a field is a separate migration.
fun Poi.propertiesJson(): JsonObject = perTypeProperties(this)

private fun perTypeProperties(poi: Poi): JsonObject =
    when (poi) {
        is Poi.Campground ->
            buildJsonObject {
                put("amenities", JsonArray(poi.amenities.map { JsonPrimitive(it) }))
                put("activities", JsonArray(poi.activities.map { JsonPrimitive(it) }))
                poi.sites?.let { put("sites", JsonPrimitive(it)) }
                poi.season?.let { put("season", JsonPrimitive(it)) }
                poi.near?.let { put("near", JsonPrimitive(it)) }
                poi.photoUrl?.let { put("photo_url", JsonPrimitive(it)) }
            }
        is Poi.Supercharger ->
            buildJsonObject {
                put("stall_count", JsonPrimitive(poi.stallCount))
                put("max_power_kw", JsonPrimitive(poi.maxPowerKw))
                poi.facility?.let { put("facility", JsonPrimitive(it)) }
                if (poi.pricebooks.isNotEmpty()) put("pricebooks", JsonArray(poi.pricebooks))
            }
        is Poi.Park ->
            buildJsonObject {
                put("designation", JsonPrimitive(poi.designation))
                poi.officialName?.let { put("official_name", JsonPrimitive(it)) }
                poi.acres?.let { put("acres", JsonPrimitive(it)) }
            }
        is Poi.PlanetFitness ->
            buildJsonObject {
                poi.openingHours?.let { put("opening_hours", JsonPrimitive(it)) }
            }
    }
