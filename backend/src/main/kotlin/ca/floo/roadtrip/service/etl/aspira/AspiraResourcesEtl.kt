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
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Terminal ETL for the `reservable_data` section. Reads the per-park
 * `/api/resourcelocation/resources` envelopes captured by
 * `scripts/fetch_aspira_inventory.py` and emits one reservable per
 * Aspira resource (per-individual-site).
 *
 * Two inputs per row:
 *   1. `aspira-inventory-{tenant}` — per-park named-site catalog
 *      (`/api/resourcelocation/resources`). Source of truth for both
 *      "what reservables exist" and "what we know about them": names,
 *      descriptions, allowed equipment, capacity, attribute IDs.
 *   2. `aspira-maps-{tenant}` — same /api/maps capture
 *      [AspiraLeavesEtl] reads. Walked here for leaf metadata
 *      (transactionLocationId, name, parent name) used to label each
 *      resource by its parent loop. Cross-row etl refs aren't supported
 *      (RFC 0008 / [PoiRegistry] validator), so this ETL re-walks the
 *      maps tree itself.
 *
 * Inventory is per-park; the maps tree is per-leaf. Each inventory
 * record carries `mapIds[]` with the leaf(s) it belongs to, so loop
 * labeling stays the join `mapIds[0] → AspiraLeaf.name` against the
 * walked-maps index.
 *
 * **Why this shape rather than driving off `/api/availability/map`'s
 * `resourceAvailabilities` block:** the per-leaf availability call
 * returns an empty `resourceAvailabilities` for parent leaves whose
 * children carry the actual sites. Inventory enumerates every
 * reservable site under a park regardless, so the catalog is complete.
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
     * YAML data_source slug for the /api/maps capture
     * (`aspira-maps-{tenant}`). One single-envelope input. Used to
     * resolve each resource's `mapIds[]` to a parent leaf and label
     * the reservable's `loop`.
     */
    val mapsInputSlug: String,
    /**
     * YAML data_source slug for the per-park inventory capture
     * (`aspira-inventory-{tenant}`). Multi-part: one envelope per park.
     * The catalog drives the output set: one reservable per inventory
     * record.
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
        val slugs = inputs.dataSourceSlugs()
        require(slugs.contains(mapsInputSlug)) {
            "$etlSlug: missing required input '$mapsInputSlug' in $slugs"
        }
        require(slugs.contains(inventoryInputSlug)) {
            "$etlSlug: missing required input '$inventoryInputSlug' in $slugs"
        }
        val inventoryEnvelopes = inputs.envelopes(inventoryInputSlug)
        require(inventoryEnvelopes.isNotEmpty()) {
            "$etlSlug: no envelopes in '$inventoryInputSlug' (run fetch_aspira_inventory.py first)"
        }
        val mapsArray = inputs.envelope(mapsInputSlug).payload.jsonArray
        return Parsed(
            inventory = inventoryEnvelopes,
            maps = mapsArray,
        )
    }

    override fun validate(dto: Parsed): ValidationResult<Parsed> =
        when {
            dto.inventory.isEmpty() ->
                ValidationResult.Bad(null, listOf("$etlSlug: empty inventory input"))

            dto.maps.isEmpty() ->
                ValidationResult.Bad(null, listOf("$etlSlug: empty /api/maps payload"))

            else -> ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: Parsed,
        ctx: TransformCtx,
    ): ReservableEtlOutput {
        // Maps tree → mapId-keyed leaf metadata. Each inventory record
        // carries `mapIds[]`; we look up the first one to label the
        // reservable's `loop`.
        val leavesByMapId =
            AspiraLeavesWalk
                .walk(dto.maps)
                .associateBy { it.mapId }

        val out = mutableListOf<ReservableRepo.Input>()
        var unmatchedLeaf = 0
        var totalRecords = 0
        for (envelope in dto.inventory) {
            val payload = envelope.payload as? JsonObject ?: continue
            for ((resourceId, raw) in payload) {
                if (resourceId.isEmpty()) continue
                val obj = raw as? JsonObject ?: continue
                val inv = parseResourceInventory(resourceId, obj) ?: continue
                totalRecords++
                val leafMapId = inv.firstMapId
                val leaf = leafMapId?.let { leavesByMapId[it] }
                if (leafMapId != null && leaf == null) unmatchedLeaf++
                out +=
                    ReservableRepo.Input(
                        rid = ReservableId(ReservableType.SITE, vendor, resourceId),
                        // Short label from /api/resourcelocation/resources
                        // (`localizedValues[0].name`) — e.g. "OFC13", "B7".
                        name = inv.name,
                        // Loop is the parent leaf's name from /api/maps
                        // (PC's "AREA WHITE RIVER" analogue).
                        loop = leaf?.name,
                        // Site-type label requires resolving
                        // `resourceCategoryId` against /api/resourcecategory.
                        // Defer that fetch — for now pass through the raw
                        // id in the JSON blob and leave the column null.
                        siteType = null,
                        raw = buildResourceRaw(inv = inv, leaf = leaf),
                    )
            }
        }
        log.info(
            "$etlSlug: emitted {} reservables from {} inventory envelopes ({} resources with no matching leaf in /api/maps)",
            out.size,
            dto.inventory.size,
            unmatchedLeaf,
        )
        if (totalRecords != out.size) {
            log.warn("$etlSlug: parsed {} inventory records but emitted {} reservables", totalRecords, out.size)
        }
        return ReservableEtlOutput(reservables = out)
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
        val resourceLocationId = obj["resourceLocationId"]?.jsonPrimitive?.long
        val maxCapacity = obj["maxCapacity"]?.jsonPrimitive?.intOrNull
        val minCapacity = obj["minCapacity"]?.jsonPrimitive?.intOrNull
        val maxBoatLength = obj["maxBoatLength"]?.jsonPrimitive?.intOrNull
        val allowedEquipment = obj["allowedEquipment"] as? JsonArray
        val definedAttributes = obj["definedAttributes"] as? JsonArray
        val mapIds =
            (obj["mapIds"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.long.takeIf { _ -> it.jsonPrimitive.contentOrNull != null } }
                ?: emptyList()
        return ResourceInventory(
            resourceId = resourceId,
            name = name,
            description = description,
            resourceCategoryId = resourceCategoryId,
            resourceLocationId = resourceLocationId,
            maxCapacity = maxCapacity,
            minCapacity = minCapacity,
            maxBoatLength = maxBoatLength,
            allowedEquipment = allowedEquipment,
            definedAttributes = definedAttributes,
            mapIds = mapIds,
        )
    }

    /**
     * Build the `raw` JSON we persist on the reservable. The per-resource
     * upstream availability array is intentionally *not* stored — that's
     * availability data and lives elsewhere. We keep the catalog signal:
     * who this resource is, how to find its parent POI, and the
     * inventory attributes the booking SPA shows.
     */
    private fun buildResourceRaw(
        inv: ResourceInventory,
        leaf: AspiraLeaf?,
    ): JsonObject =
        buildJsonObject {
            put("resource_id", inv.resourceId)
            // Parent linkage: prefer leaf metadata when we matched
            // mapIds[0] to a known leaf. Fall back to the
            // resourceLocationId from the inventory record itself so
            // the joiner has *something* to match POIs by.
            if (leaf != null) {
                put("_parent_aspira_map_id", leaf.mapId)
                put("_parent_aspira_txn_loc", leaf.transactionLocationId)
                if (leaf.resourceLocationId != null) {
                    put("_parent_aspira_resource_loc", leaf.resourceLocationId)
                }
                put("_parent_leaf_name", leaf.name)
                if (leaf.parentName != null) {
                    put("_parent_leaf_parent_name", leaf.parentName)
                }
            } else {
                if (inv.firstMapId != null) {
                    put("_parent_aspira_map_id", inv.firstMapId)
                }
                if (inv.resourceLocationId != null) {
                    put("_parent_aspira_resource_loc", inv.resourceLocationId)
                }
            }
            if (inv.description != null) {
                put("description", inv.description)
            }
            if (inv.resourceCategoryId != null) {
                put("resource_category_id", inv.resourceCategoryId)
            }
            if (inv.maxCapacity != null) {
                put("max_capacity", inv.maxCapacity)
            }
            if (inv.minCapacity != null) {
                put("min_capacity", inv.minCapacity)
            }
            if (inv.maxBoatLength != null) {
                put("max_boat_length", inv.maxBoatLength)
            }
            if (inv.allowedEquipment != null) {
                put("allowed_equipment", inv.allowedEquipment)
            }
            if (inv.definedAttributes != null) {
                // Strip Aspira's outer wrapping (`attributeVisibility`,
                // duplicate `attributeId`/`attributeDefinitionId`) and
                // keep just (id, value, values) so downstream consumers
                // don't have to know Aspira-shaped JSON.
                put("defined_attributes", flattenAttributes(inv.definedAttributes))
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
        val inventory: List<Envelope>,
        val maps: JsonArray,
    )

    /** A single reservable's catalog row, normalized out of Aspira's wrapping. */
    private data class ResourceInventory(
        val resourceId: String,
        val name: String?,
        val description: String?,
        val resourceCategoryId: Int?,
        val resourceLocationId: Long?,
        val maxCapacity: Int?,
        val minCapacity: Int?,
        val maxBoatLength: Int?,
        val allowedEquipment: JsonArray?,
        val definedAttributes: JsonArray?,
        val mapIds: List<Long>,
    ) {
        val firstMapId: Long? get() = mapIds.firstOrNull()
    }
}
