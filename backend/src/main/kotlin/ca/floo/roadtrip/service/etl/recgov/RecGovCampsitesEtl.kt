package ca.floo.roadtrip.service.etl.recgov

import ca.floo.roadtrip.models.Envelope
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.ValidationResult
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.etl.InputBundle
import ca.floo.roadtrip.service.etl.ReservableEtlOutput
import ca.floo.roadtrip.service.etl.SourceEtl
import ca.floo.roadtrip.service.etl.TransformCtx
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Terminal ETL for the `reservable_data` section. Reads per-facility
 * envelopes captured by `scripts/fetch_recgov_campsites.py` and emits one
 * reservable per campsite.
 *
 * **No POI knowledge.** Linking reservables to their parent campground
 * POI is the joiner's job (see [PoiReservableJoiner], wired in PR 4 as
 * `RecgovPoiReservableJoiner`). Keeping the ETL POI-agnostic means it
 * doesn't need to know how rec.gov campgrounds get keyed in `pois`
 * (today: `pois.source = federal-campgrounds`, `source_id = FacilityID`).
 *
 * The full upstream campsite blob is preserved verbatim in `raw` for the
 * joiner to read and for forensic queries — RFC 0008's data trust
 * principle. The joiner uses `raw->>'campsite_id'` as the join key.
 *
 * Multi-part input: the fetcher writes one file per facility under
 * `data/raw/recgov-campsites/<ts>/facility-<id>.json`. We get all of
 * them via `inputs.soleEnvelopes()` and emit one reservable per
 * `campsites` map entry.
 */
class RecGovCampsitesEtl(
    override val etlSlug: String,
) : SourceEtl<List<Envelope>, ReservableEtlOutput> {
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
    ): ReservableEtlOutput {
        val reservables = mutableListOf<ReservableRepo.Input>()
        for (envelope in dto) {
            // FacilityID lives in the captured request URL path. The
            // upstream campsite blob doesn't carry it; we need it for the
            // joiner to match against pois.source_id. Inject it as a
            // synthetic key on each campsite's `raw` so the joiner has
            // a stable place to read it.
            val facilityId = parseFacilityIdFromUrl(envelope.request.url) ?: continue
            val payload = envelope.payload as? JsonObject ?: continue
            val campsites = payload["campsites"] as? JsonObject ?: continue
            for ((campsiteId, element) in campsites) {
                val raw = element as? JsonObject ?: continue
                val rid = ReservableId(ReservableType.SITE, "recgov", campsiteId)
                reservables +=
                    ReservableRepo.Input(
                        rid = rid,
                        name = (raw["site"] as? JsonPrimitive)?.contentOrNull,
                        loop = (raw["loop"] as? JsonPrimitive)?.contentOrNull,
                        siteType = (raw["campsite_type"] as? JsonPrimitive)?.contentOrNull,
                        // Preserve the full upstream campsite blob — equipment
                        // types, attributes, max_num_people, the lot — and
                        // tack on a synthetic `_parent_facility_id` so the
                        // joiner has a place to read it. Underscore prefix
                        // signals "synthetic, not from upstream"; we don't
                        // expect rec.gov to ever ship a field with that name.
                        raw = withSynthetic(raw, "_parent_facility_id", facilityId),
                    )
            }
        }
        return ReservableEtlOutput(reservables = reservables)
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

    /** Return a copy of [obj] with [key] set to [value]. */
    private fun withSynthetic(
        obj: JsonObject,
        key: String,
        value: String,
    ): JsonObject =
        buildJsonObject {
            for ((k, v) in obj) put(k, v)
            put(key, value)
        }
}
