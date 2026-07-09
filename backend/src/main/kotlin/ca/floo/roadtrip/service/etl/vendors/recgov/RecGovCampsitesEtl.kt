package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlOutput
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlRecord
import ca.floo.roadtrip.service.etl.framework.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.reservableTagKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Terminal ETL for the `reservable_data` section. Reads per-facility
 * envelopes captured by `scripts/fetch_recgov_campsites.py` and emits one
 * reservable per campsite.
 *
 * Parent linking is explicit on every emitted row: `parentVendor` is the
 * federal campground ETL slug and `parentVendorRefId` is `recgov-{FacilityID}`.
 * CanonicalCatalogRepo resolves that pair through campground vendor refs.
 *
 * The full upstream campsite blob is preserved verbatim in `sourcePayload`
 * for forensic queries — RFC 0008's data trust principle.
 *
 * Multi-part input: the fetcher writes one file per facility under
 * `data/raw/recgov-campsites/<ts>/facility-<id>.json`. We get all of
 * them via `inputs.soleEnvelopes()` and emit one reservable per
 * `campsites` map entry.
 */
class RecGovCampsitesEtl(
    override val etlSlug: String,
) : SourceEtl<List<Envelope>, CampsiteEtlOutput> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): List<Envelope> {
        val envelopes = inputs.soleEnvelopes()
        require(envelopes.isNotEmpty()) {
            "$etlSlug: no envelopes captured (run fetch_recgov_campsites.py first)"
        }
        return envelopes
    }

    override fun validate(dto: List<Envelope>): ValidationResult<List<Envelope>> =
        if (dto.isEmpty()) ValidationResult.Bad(null, listOf("$etlSlug: empty input")) else ValidationResult.Ok(dto)

    override fun transform(
        dto: List<Envelope>,
        ctx: TransformCtx,
    ): CampsiteEtlOutput {
        val campsites = mutableListOf<CampsiteEtlRecord>()
        for (envelope in dto) {
            // FacilityID lives in the captured request URL path. The
            // upstream campsite blob doesn't carry it, but parent resolution
            // needs it for `parentVendorRefId = recgov-{FacilityID}`.
            val facilityId = parseFacilityIdFromUrl(envelope.request.url) ?: continue
            val payload = envelope.payload as? JsonObject ?: continue
            val rawCampsites = payload["campsites"] as? JsonObject ?: continue
            for ((campsiteId, element) in rawCampsites) {
                val raw = element as? JsonObject ?: continue
                val tags = buildCampsiteTags(raw)
                val siteName = raw.stringField("site") ?: campsiteId
                val campsiteType = raw.stringField("campsite_type")
                campsites +=
                    CampsiteEtlRecord(
                        vendor = VENDOR,
                        vendorRefId = campsiteId,
                        parentVendor = PARENT_CAMPGROUND_VENDOR,
                        parentVendorRefId = "$PARENT_CAMPGROUND_REF_PREFIX$facilityId",
                        name = siteName,
                        loopName = raw.stringField("loop"),
                        kind = campsiteType ?: DEFAULT_CAMPSITE_KIND,
                        kindListed = campsiteType,
                        equipment = raw["equipment_types"] as? JsonArray,
                        maxPeople = raw["max_num_people"]?.jsonPrimitive?.intOrNull,
                        sourcePayload =
                            withSynthetic(
                                raw,
                                mapOf(
                                    "_parent_facility_id" to JsonPrimitive(facilityId),
                                    "_roadtrip_tags" to tags,
                                ),
                            ),
                        vendorRefPayload =
                            buildJsonObject {
                                put("recgov_id", campsiteId)
                                put("_parent_facility_id", facilityId)
                            },
                    )
            }
        }
        return CampsiteEtlOutput(campsites = campsites)
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun buildCampsiteTags(raw: JsonObject): JsonObject =
        buildJsonObject {
            val capacity =
                buildJsonObject {
                    raw["min_num_people"]?.jsonPrimitive?.intOrNull?.let { put("min", it) }
                    raw["max_num_people"]?.jsonPrimitive?.intOrNull?.let { put("max", it) }
                }
            if (capacity.isNotEmpty()) {
                put("capacity", capacity)
            }

            val equipment = raw["equipment_types"] as? JsonArray
            if (equipment != null && equipment.isNotEmpty()) {
                put("equipment", equipment)
            }

            raw["campsite_reserve_type"]?.jsonPrimitive?.contentOrNull?.let { put("reserve_type", it) }
            raw["type_of_use"]?.jsonPrimitive?.contentOrNull?.let { put("use", it) }
            raw["capacity_rating"]?.jsonPrimitive?.contentOrNull?.let { put("capacity_rating", it) }

            val attributes = recgovAttributeTags(raw["attributes"] as? JsonArray)
            if (attributes.isNotEmpty()) {
                put("attributes", attributes)
            }
        }

    private fun recgovAttributeTags(attributes: JsonArray?): JsonObject {
        if (attributes == null) return JsonObject(emptyMap())
        return buildJsonObject {
            for (rawAttribute in attributes) {
                val attr = rawAttribute as? JsonObject ?: continue
                val name = attr["attribute_name"]?.jsonPrimitive?.contentOrNull ?: continue
                val key = reservableTagKey(name)
                if (key.isEmpty()) continue
                val value = attr["attribute_value"]?.jsonPrimitive?.contentOrNull ?: continue
                put(key, value)
            }
        }
    }

    /**
     * Pull the FacilityID from the URL the fetcher captured. URL shape:
     *   .../api/camps/availability/campground/{FacilityID}/month?start_date=...
     * Returns null when the marker isn't found so the validator drops
     * the envelope rather than crashing the run.
     */
    private fun parseFacilityIdFromUrl(url: String): String? {
        val marker = "/campground/"
        val start = url.indexOf(marker).takeIf { it >= 0 } ?: return null
        val tail = url.substring(start + marker.length)
        val end = tail.indexOfAny(charArrayOf('/', '?'))
        val raw = if (end < 0) tail else tail.substring(0, end)
        return raw.takeIf { it.isNotEmpty() }
    }

    /** Return a copy of [obj] with synthetic key/value pairs added. */
    private fun withSynthetic(
        obj: JsonObject,
        values: Map<String, kotlinx.serialization.json.JsonElement>,
    ): JsonObject =
        buildJsonObject {
            for ((k, v) in obj) put(k, v)
            for ((k, v) in values) put(k, v)
        }

    private companion object {
        const val VENDOR = "recgov"
        const val PARENT_CAMPGROUND_VENDOR = "federal-campgrounds"
        const val PARENT_CAMPGROUND_REF_PREFIX = "recgov-"
    }
}
