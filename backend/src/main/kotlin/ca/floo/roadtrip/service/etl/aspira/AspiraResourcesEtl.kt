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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Terminal ETL for the `reservable_data` section. Reads per-leaf
 * `/api/availability/map` envelopes captured by
 * `scripts/fetch_aspira_resources.py` and emits one reservable per
 * Aspira resource (per-individual-site).
 *
 * Three inputs per row:
 *   1. `aspira-resources-{tenant}` — per-leaf availability captures.
 *      Each envelope's payload has a `resourceAvailabilities` JSON object
 *      keyed by resourceId; that's the resource ID set we monitor.
 *   2. `aspira-maps-{tenant}` — same /api/maps capture
 *      [AspiraLeavesEtl] reads. Walked here for leaf metadata
 *      (transactionLocationId, name, parent name) used to label each
 *      resource by its parent loop. Cross-row etl refs aren't supported
 *      (RFC 0008 / [PoiRegistry] validator), so this ETL re-walks the
 *      maps tree itself rather than depending on `aspira-leaves-{tenant}`.
 *   3. `aspira-inventory-{tenant}` — per-park named-site catalog
 *      (`/api/resourcelocation/resources`). Supplies
 *      `localizedValues[].name` ("OFC13"), `description`
 *      ("C13 Phantom RV Pad"), `allowedEquipment`, capacity, and
 *      `definedAttributes` for every reservable site at that park.
 *      The ETL still emits a row per resource even when this capture
 *      is missing a particular `resourceId`; in that case the row's
 *      `name`/`description` columns stay null. Use that to scope what
 *      enrichment is "best-effort" vs strict — see [ResourceInventory].
 *
 * **No POI knowledge.** Linking these reservables to their parent
 * Aspira POI is the joiner's job. Resources land in the catalog with
 * synthetic `_parent_*` fields the joiner reads to find the right POI.
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
     * YAML data_source slug for the per-park inventory capture
     * (`aspira-inventory-{tenant}`). The catalog source for
     * `reservables.name` and richer attributes. Optional at runtime:
     * if no envelopes exist yet, the ETL skips enrichment but still
     * emits resource rows. See class kdoc.
     */
    val inventoryInputSlug: String,
    /**
     * `aspira_wa` / `aspira_bc` / `aspira_pc`. Stamped into every
     * emitted [ReservableId.vendor]. ReservableId disallows ':' in
     * vendor, so we use underscore-separated tenant codes.
     */
    val vendor: String,
) : SourceEtl<AspiraResourcesEtl.Parsed, ReservableEtlOutput> {
    override val multiPart: Boolean = true

    private val log = LoggerFactory.getLogger(javaClass)

    override fun parse(inputs: InputBundle): Parsed {
        // Inputs are addressed by name so the YAML's order doesn't matter:
        //  - mapsInputSlug      → single-envelope /api/maps capture
        //  - inventoryInputSlug → multi-part /api/resourcelocation/resources
        //  - everything else    → the per-leaf availability capture
        val slugs = inputs.dataSourceSlugs()
        require(slugs.contains(mapsInputSlug)) {
            "$etlSlug: missing required input '$mapsInputSlug' in $slugs"
        }
        require(slugs.contains(inventoryInputSlug)) {
            "$etlSlug: missing required input '$inventoryInputSlug' in $slugs"
        }
        val resourcesSlug =
            slugs.firstOrNull { it != mapsInputSlug && it != inventoryInputSlug }
                ?: error(
                    "$etlSlug: expected three inputs (resources + maps + inventory); got $slugs",
                )
        val resourceEnvelopes = inputs.envelopes(resourcesSlug)
        require(resourceEnvelopes.isNotEmpty()) {
            "$etlSlug: no envelopes in '$resourcesSlug' (run fetch_aspira_resources.py first)"
        }
        val mapsArray = inputs.envelope(mapsInputSlug).payload.jsonArray
        val inventoryEnvelopes = inputs.envelopes(inventoryInputSlug)
        return Parsed(
            resources = resourceEnvelopes,
            maps = mapsArray,
            inventory = inventoryEnvelopes,
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

        // Index the inventory captures by resourceId. One envelope per park
        // contains every reservable site at that park; we flatten across
        // parks so per-resource lookup is O(1). Multiple parks can never
        // share a resourceId — Aspira's IDs are tenant-global and unique.
        val inventoryByResourceId = indexInventoryByResourceId(dto.inventory)
        log.info(
            "$etlSlug: inventory index built — {} resources from {} envelopes",
            inventoryByResourceId.size,
            dto.inventory.size,
        )

        val out = mutableListOf<ReservableRepo.Input>()
        var enriched = 0
        for (envelope in dto.resources) {
            val mapId = parseMapIdFromUrl(envelope.request.url) ?: continue
            val payload = envelope.payload as? JsonObject ?: continue
            val resourceAvailabilities = payload[RESOURCE_AVAILABILITIES] as? JsonObject ?: continue
            val leaf = leavesByMapId[mapId] // may be null if maps/resources captures are out of sync

            for ((resourceId, _) in resourceAvailabilities) {
                if (resourceId.isEmpty()) continue
                val rid = ReservableId(ReservableType.SITE, vendor, resourceId)
                val inv = inventoryByResourceId[resourceId]
                if (inv != null) enriched++
                out +=
                    ReservableRepo.Input(
                        rid = rid,
                        // Short label from /api/resourcelocation/resources
                        // (`localizedValues[0].name`) — e.g. "OFC13", "B7".
                        // Falls back to null when the inventory capture is
                        // absent or out of sync with resources.
                        name = inv?.name,
                        // Loop is the parent leaf's name from /api/maps
                        // (PC's "AREA WHITE RIVER" analogue). The inventory
                        // catalog doesn't carry loop info; the maps tree
                        // does. Keep using leaf.name even when we have
                        // inventory.
                        loop = leaf?.name,
                        // Site-type label requires resolving
                        // `resourceCategoryId` against /api/resourcecategory.
                        // Defer that fetch — for now pass through the raw
                        // id in the JSON blob and leave the column null.
                        siteType = null,
                        raw =
                            buildResourceRaw(
                                resourceId = resourceId,
                                mapId = mapId,
                                leaf = leaf,
                                inventory = inv,
                            ),
                    )
            }
        }
        log.info(
            "$etlSlug: emitted {} reservables ({} enriched with inventory)",
            out.size,
            enriched,
        )
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
     * Flatten every park's `/api/resourcelocation/resources` body into a
     * single `resourceId → [ResourceInventory]` index. Skips envelopes
     * whose payload isn't an object (defensive: WAF-blocked captures
     * would have been rejected at fetch time, but this keeps the ETL
     * resilient to a stray malformed envelope).
     */
    private fun indexInventoryByResourceId(envelopes: List<Envelope>): Map<String, ResourceInventory> {
        if (envelopes.isEmpty()) return emptyMap()
        val out = mutableMapOf<String, ResourceInventory>()
        for (env in envelopes) {
            val payload = env.payload as? JsonObject ?: continue
            for ((resourceId, raw) in payload) {
                val obj = raw as? JsonObject ?: continue
                val inv = parseResourceInventory(resourceId, obj) ?: continue
                out[resourceId] = inv
            }
        }
        return out
    }

    private fun parseResourceInventory(
        resourceId: String,
        obj: JsonObject,
    ): ResourceInventory? {
        val localized = obj["localizedValues"] as? JsonArray
        val firstLocale = localized?.firstOrNull() as? JsonObject
        val name = firstLocale?.get("name")?.jsonPrimitive?.contentOrNull
        val description = firstLocale?.get("description")?.jsonPrimitive?.contentOrNull
        val resourceCategoryId = obj["resourceCategoryId"]?.jsonPrimitive?.intOrNull
        val maxCapacity = obj["maxCapacity"]?.jsonPrimitive?.intOrNull
        val minCapacity = obj["minCapacity"]?.jsonPrimitive?.intOrNull
        val maxBoatLength = obj["maxBoatLength"]?.jsonPrimitive?.intOrNull
        val allowedEquipment = obj["allowedEquipment"] as? JsonArray
        val definedAttributes = obj["definedAttributes"] as? JsonArray
        return ResourceInventory(
            resourceId = resourceId,
            name = name,
            description = description,
            resourceCategoryId = resourceCategoryId,
            maxCapacity = maxCapacity,
            minCapacity = minCapacity,
            maxBoatLength = maxBoatLength,
            allowedEquipment = allowedEquipment,
            definedAttributes = definedAttributes,
        )
    }

    /**
     * Build the `raw` JSON we persist on the reservable. The per-resource
     * upstream availability array is intentionally *not* stored — that's
     * availability data and lives elsewhere. We keep the catalog signal:
     * who this resource is, how to find its parent POI, and (when
     * available) the inventory attributes the booking SPA shows.
     */
    private fun buildResourceRaw(
        resourceId: String,
        mapId: Long,
        leaf: AspiraLeaf?,
        inventory: ResourceInventory?,
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
            if (inventory != null) {
                if (inventory.description != null) {
                    put("description", inventory.description)
                }
                if (inventory.resourceCategoryId != null) {
                    put("resource_category_id", inventory.resourceCategoryId)
                }
                if (inventory.maxCapacity != null) {
                    put("max_capacity", inventory.maxCapacity)
                }
                if (inventory.minCapacity != null) {
                    put("min_capacity", inventory.minCapacity)
                }
                if (inventory.maxBoatLength != null) {
                    put("max_boat_length", inventory.maxBoatLength)
                }
                if (inventory.allowedEquipment != null) {
                    put("allowed_equipment", inventory.allowedEquipment)
                }
                if (inventory.definedAttributes != null) {
                    // Strip Aspira's outer wrapping (`attributeVisibility`,
                    // duplicate `attributeId`/`attributeDefinitionId`) and
                    // keep just (id, value, values) so downstream consumers
                    // don't have to know Aspira-shaped JSON.
                    put("defined_attributes", flattenAttributes(inventory.definedAttributes))
                }
            }
        }

    private fun flattenAttributes(attrs: JsonArray): JsonArray =
        buildJsonArray {
            for (raw in attrs) {
                val a = raw as? JsonObject ?: continue
                add(
                    buildJsonObject {
                        a["attributeDefinitionId"]?.jsonPrimitive?.intOrNull?.let {
                            put("definition_id", it)
                        }
                        a["value"]?.let { put("value", it) }
                        a["values"]?.let { put("values", it) }
                    },
                )
            }
        }

    /** Parsed shape passed through validate→transform. */
    data class Parsed(
        val resources: List<Envelope>,
        val maps: JsonArray,
        val inventory: List<Envelope>,
    )

    /** A single reservable's catalog row, normalized out of Aspira's wrapping. */
    private data class ResourceInventory(
        val resourceId: String,
        val name: String?,
        val description: String?,
        val resourceCategoryId: Int?,
        val maxCapacity: Int?,
        val minCapacity: Int?,
        val maxBoatLength: Int?,
        val allowedEquipment: JsonArray?,
        val definedAttributes: JsonArray?,
    )

    private companion object {
        const val RESOURCE_AVAILABILITIES = "resourceAvailabilities"
    }
}
