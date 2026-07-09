package ca.floo.roadtrip.models.domain

import kotlinx.serialization.json.JsonElement

/**
 * A reservable as we store it. Catalog data — names, loop, type, the raw
 * upstream blob — refreshed by ETL, not request-time. Per-day availability
 * is computed live by the AvailabilityProvider; not stored here.
 *
 * `id` is the canonical campsite pk (`campsites.id`) used for joins and API
 * payloads. `vendor` / `vendorId` identify the selected provider-side row for
 * adapter calls and booking URLs.
 *
 * `raw` preserves the full upstream JSON blob exactly as the vendor sent
 * it (rec.gov campsite object, Aspira resource detail). Data trust:
 * future audit / forensic queries see the source of truth, not what we
 * chose to project.
 *
 * `tags` is the provider-neutral projection ETLs build for common catalog
 * traits: capacity, equipment, and named attributes. It is safe for UI and
 * search code to depend on; unlike `raw`, it should not expose vendor id
 * vocabularies as its primary interface.
 *
 * RFC 0008 §"Data model".
 */
data class Reservable(
    val id: Long,
    val vendor: String,
    val vendorId: String,
    val name: String?,
    val loop: String?,
    val siteType: String?,
    val raw: JsonElement?,
    val tags: JsonElement? = null,
    val providerRef: JsonElement? = null,
)
