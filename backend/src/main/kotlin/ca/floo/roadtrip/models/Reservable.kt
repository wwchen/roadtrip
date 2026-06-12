package ca.floo.roadtrip.models

import kotlinx.serialization.json.JsonElement

/**
 * A reservable as we store it. Catalog data — names, loop, type, the raw
 * upstream blob — refreshed by ETL, not request-time. Per-day availability
 * is computed live by the BookingProvider; not stored here.
 *
 * `id` is the internal Postgres pk for joins (`reservables.id`). The
 * stable, externally-meaningful identity is the [ReservableId] composite.
 *
 * `raw` preserves the full upstream JSON blob exactly as the vendor sent
 * it (rec.gov campsite object, Aspira resource detail). Data trust:
 * future audit / forensic queries see the source of truth, not what we
 * chose to project.
 *
 * RFC 0008 §"Data model".
 */
data class Reservable(
    val id: Long,
    val rid: ReservableId,
    val name: String?,
    val loop: String?,
    val siteType: String?,
    val raw: JsonElement?,
)
