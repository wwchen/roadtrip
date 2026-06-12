package ca.floo.roadtrip.service.etl.aspira

import ca.floo.roadtrip.models.Envelope
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.ValidationResult
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.etl.InputBundle
import ca.floo.roadtrip.service.etl.ReservableEtlOutput
import ca.floo.roadtrip.service.etl.SourceEtl
import ca.floo.roadtrip.service.etl.TransformCtx
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

/**
 * Terminal ETL for the `reservable_data` section. Reads per-leaf
 * `/api/availability/map` envelopes captured by
 * `scripts/fetch_aspira_resources.py` and emits one reservable per
 * Aspira resource (per-individual-site).
 *
 * Two inputs per row:
 *   1. `aspira-resources-{tenant}` — the per-leaf availability captures.
 *      Each envelope's payload has a `resourceAvailabilities` JSON object
 *      keyed by resourceId; that's the catalog signal.
 *   2. `aspira-maps-{tenant}` — the same /api/maps capture
 *      [AspiraLeavesEtl] reads. We walk it via [AspiraLeavesWalk] to
 *      label each resource with its parent leaf's name + IDs. Cross-row
 *      etl refs aren't supported (RFC 0008 / [PoiRegistry] validator), so
 *      this ETL re-walks the maps tree itself rather than depending on
 *      `aspira-leaves-{tenant}` from the poi_data section.
 *
 * **No POI knowledge.** Linking these reservables to their parent
 * Aspira POI is the joiner's job (PR 4). Resources land in the catalog
 * with synthetic `_parent_*` fields the joiner reads to find the right
 * POI.
 *
 * Vendor strings: `aspira_wa` / `aspira_bc` / `aspira_pc` — ReservableId
 * disallows colons in the vendor field, so the per-tenant suffix uses
 * underscore. Three slug instances of this class, one per tenant. The
 * vendor literal is bound by constructor arg.
 */
class AspiraResourcesEtl(
    override val etlSlug: String,
    /**
     * YAML data_source slug paired to this ETL's `aspira-resources-*`
     * input — i.e. the `aspira-maps-{tenant}` capture for the same
     * tenant. Declared so the YAML reads explicitly; resolved by the
     * orchestrator into `bundle.envelope(mapsInputSlug)`.
     */
    val mapsInputSlug: String,
    /**
     * `aspira_wa` / `aspira_bc` / `aspira_pc`. Stamped into every
     * emitted [ReservableId.vendor]. ReservableId disallows ':' in
     * vendor, so we use underscore-separated tenant codes.
     */
    val vendor: String,
) : SourceEtl<AspiraResourcesEtl.Parsed, ReservableEtlOutput> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): Parsed {
        // The `aspira-resources-*` capture is multi-part (one envelope per
        // leaf), so it's the *first* data_source slug in the YAML inputs:
        // list. Anything beyond it is required to be a single-envelope
        // companion data_source — today that's `aspira-maps-{tenant}`.
        val slugs = inputs.dataSourceSlugs()
        require(slugs.size == 2) {
            "$etlSlug: expected 2 data_source inputs (resources + maps), got ${slugs.size}: $slugs"
        }
        val resourceEnvelopes = inputs.envelopes(slugs[0])
        require(resourceEnvelopes.isNotEmpty()) {
            "$etlSlug: no envelopes in '${slugs[0]}' (run fetch_aspira_resources.py first)"
        }
        val mapsEnvelope = inputs.envelope(mapsInputSlug)
        val mapsArray = mapsEnvelope.payload.jsonArray
        return Parsed(
            resources = resourceEnvelopes,
            maps = mapsArray,
        )
    }

    override fun validate(dto: Parsed): ValidationResult<Parsed> =
        when {
            dto.resources.isEmpty() ->
                ValidationResult.Bad(null, listOf("$etlSlug: empty resources input"))

            dto.maps.isEmpty() ->
                ValidationResult.Bad(null, listOf("$etlSlug: empty /api/maps payload"))

            else -> ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: Parsed,
        ctx: TransformCtx,
    ): ReservableEtlOutput {
        // Index the maps tree by mapId — each resources envelope is
        // captured at one mapId and we need the leaf metadata
        // (transactionLocationId, name, parent name) to label the
        // resources from that envelope.
        val leavesByMapId =
            AspiraLeavesWalk
                .walk(dto.maps)
                .associateBy { it.mapId }

        val out = mutableListOf<ReservableRepo.Input>()
        for (envelope in dto.resources) {
            val mapId = parseMapIdFromUrl(envelope.request.url) ?: continue
            val payload = envelope.payload as? JsonObject ?: continue
            val resourceAvailabilities = payload[RESOURCE_AVAILABILITIES] as? JsonObject ?: continue
            val leaf = leavesByMapId[mapId] // may be null if the maps/resources captures are out of sync

            for ((resourceId, _) in resourceAvailabilities) {
                // resourceId is what Aspira keys the per-resource status
                // array by. It's the stable identity we monitor and
                // alert on; that becomes our vendor_id.
                if (resourceId.isEmpty()) continue
                val rid = ReservableId(ReservableType.SITE, vendor, resourceId)
                out +=
                    ReservableRepo.Input(
                        rid = rid,
                        // /api/availability/map doesn't carry per-resource
                        // names. Loop is the parent leaf's name (PC's "AREA
                        // WHITE RIVER" analogue); name stays null until a
                        // future ETL pulls richer catalog data.
                        name = null,
                        loop = leaf?.name,
                        siteType = null,
                        raw =
                            buildResourceRaw(
                                resourceId = resourceId,
                                mapId = mapId,
                                leaf = leaf,
                            ),
                    )
            }
        }
        return ReservableEtlOutput(reservables = out)
    }

    /**
     * URL shape: .../api/availability/map?mapId={int}&...
     * Pull the mapId. Aspira's mapIds can be negative (Int.MIN-adjacent),
     * so parse as Long. Returns null when the marker isn't found.
     */
    private fun parseMapIdFromUrl(url: String): Long? {
        val marker = "mapId="
        val start = url.indexOf(marker).takeIf { it >= 0 } ?: return null
        val tail = url.substring(start + marker.length)
        val end = tail.indexOf('&')
        val raw = if (end < 0) tail else tail.substring(0, end)
        return raw.toLongOrNull()
    }

    /**
     * Build the `raw` JSON we persist on the reservable. The per-resource
     * upstream availability array is intentionally *not* stored — that's
     * availability data and lives elsewhere. We keep just the catalog
     * signal: who this resource is and how to find its parent POI.
     */
    private fun buildResourceRaw(
        resourceId: String,
        mapId: Long,
        leaf: AspiraLeaf?,
    ): JsonObject =
        buildJsonObject {
            put("resource_id", resourceId)
            put("_parent_aspira_map_id", mapId)
            if (leaf != null) {
                put("_parent_aspira_txn_loc", leaf.transactionLocationId)
                if (leaf.resourceLocationId != null) {
                    put("_parent_aspira_resource_loc", leaf.resourceLocationId)
                }
                put("_parent_leaf_name", leaf.name)
                if (leaf.parentName != null) {
                    put("_parent_leaf_parent_name", leaf.parentName)
                }
            }
        }

    /** Parsed shape passed through validate→transform. */
    data class Parsed(
        val resources: List<Envelope>,
        val maps: JsonArray,
    )

    private companion object {
        const val RESOURCE_AVAILABILITIES = "resourceAvailabilities"
    }
}
