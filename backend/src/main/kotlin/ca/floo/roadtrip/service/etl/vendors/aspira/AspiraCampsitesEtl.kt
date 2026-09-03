package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampsiteEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.photosPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Terminal ETL for the `campsite_data` section. Reads the per-park
 * `/api/resourcelocation/resources` envelopes captured by
 * `scripts/fetch_aspira_inventory.py` and emits one campsite per
 * Aspira resource (per-individual-site).
 *
 * Two inputs per row:
 *   1. `aspira-inventory-{tenant}` — per-park named-site catalog
 *      (`/api/resourcelocation/resources`). Source of truth for both
 *      "what campsites exist" and "what we know about them": names,
 *      descriptions, allowed equipment, capacity, attribute IDs.
 *   2. `aspira-maps-{tenant}` — /api/maps capture walked via
 *      [AspiraLeavesWalk] for leaf metadata
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
 * campsite under a park regardless, so the catalog is complete.
 *
 * Parent linking is explicit on every emitted row: the tenant vendor maps to
 * the matching Aspira campground ETL slug and `parentVendorRefId` is derived
 * from the parent campground provider-ref IDs.
 *
 * Vendor strings: `aspira_wa` / `aspira_bc` / `aspira_pc` — provider
 * vendors disallow colons, so the per-tenant suffix uses
 * underscore. Three slug instances of this class, one per tenant. The
 * vendor literal is bound by constructor arg.
 */
class AspiraCampsitesEtl(
    override val etlSlug: String,
    /**
     * YAML data_source slug for the /api/maps capture
     * (`aspira-maps-{tenant}`). One single-envelope input. Used to
     * resolve each resource's `mapIds[]` to a parent leaf and label
     * the campsite's `loop`.
     */
    val mapsInputSlug: String,
    /**
     * YAML data_source slug for the per-park inventory capture
     * (`aspira-inventory-{tenant}`). Multi-part: one envelope per park.
     * The catalog drives the output set: one campsite per inventory
     * record.
     */
    val inventoryInputSlug: String,
    /**
     * Optional YAML data_source slug for tenant dictionaries captured from
     * `/api/equipment`, `/api/resourcecategory`, and
     * `/api/attribute/filterable`. These are ETL side inputs only: loaded
     * into memory for this transform and copied into campsite.raw labels.
     */
    val dictionariesInputSlug: String? = null,
    val aspiraTenant: String,
    val parentDataProvider: DataProvider = DataProvider.ASPIRA,
) : CampsiteEtl<AspiraCampsitesEtl.Parsed> {
    override val multiPart: Boolean = true

    private val log = LoggerFactory.getLogger(javaClass)

    override fun parse(inputs: InputBundle): Sequence<ParseResult<Parsed>> =
        sequence {
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
            val dictionaries =
                dictionariesInputSlug
                    ?.takeIf { slugs.contains(it) }
                    ?.let { parseDictionaries(inputs.envelope(it).payload as? JsonObject) }
                    ?: AspiraDictionaries.empty
            val parsed =
                Parsed(
                    inventory = inventoryEnvelopes,
                    maps = mapsArray,
                    dictionaries = dictionaries,
                )
            when {
                parsed.inventory.isEmpty() ->
                    yield(ParseResult.Bad(null, listOf("$etlSlug: empty inventory input")))

                parsed.maps.isEmpty() ->
                    yield(ParseResult.Bad(null, listOf("$etlSlug: empty /api/maps payload")))

                else -> yield(ParseResult.Ok(parsed))
            }
        }

    override fun transform(
        dto: Parsed,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampsiteUpsertCandidate>> =
        sequence {
            // Maps tree → mapId-keyed leaf metadata. Each inventory record
            // carries `mapIds[]`; we look up the first one to label the
            // campsite's `loop`.
            val leaves = AspiraLeavesWalk.walk(dto.maps)
            val leavesByMapId = leaves.associateBy { it.mapId }
            val leavesByResourceLocationId =
                leaves
                    .mapNotNull { leaf -> leaf.resourceLocationId?.let { it to leaf } }
                    .toMap()

            var emitted = 0
            var unmatchedLeaf = 0
            var totalRecords = 0
            for (envelope in dto.inventory) {
                val sourceId = envelope.part ?: envelope.request.url
                val payload = envelope.payload as? JsonObject
                if (payload == null) {
                    yield(TransformResult.Bad(sourceId, listOf("expected JSON object payload")))
                    continue
                }
                for ((resourceId, raw) in payload) {
                    if (resourceId.isEmpty()) {
                        yield(TransformResult.Bad(sourceId, listOf("empty resource id")))
                        continue
                    }
                    val resourceIdLong = resourceId.toLongOrNull()
                    if (resourceIdLong == null) {
                        yield(TransformResult.Bad(resourceId, listOf("resource id is not numeric")))
                        continue
                    }
                    val obj = raw as? JsonObject
                    if (obj == null) {
                        yield(TransformResult.Bad(resourceId, listOf("expected resource JSON object")))
                        continue
                    }
                    val inv = parseResourceInventory(resourceId, obj)
                    if (inv == null) {
                        yield(TransformResult.Bad(resourceId, listOf("invalid resource inventory")))
                        continue
                    }
                    totalRecords++
                    val leafMapId = inv.firstMapId
                    val leaf = leafMapId?.let { leavesByMapId[it] }
                    val parentLeaf = leaf ?: inv.resourceLocationId?.let { leavesByResourceLocationId[it] }
                    if (leafMapId != null && leaf == null) unmatchedLeaf++
                    yield(
                        TransformResult.Ok(
                            CampsiteUpsertCandidate(
                                dataProviderRef =
                                    DataProviderRef.AspiraCampsite(
                                        tenant = aspiraTenant,
                                        resourceLocationId = resourceIdLong,
                                    ),
                                bookingProvider = BookingProvider.ASPIRA,
                                bookingProviderRef = campsiteBookingRef(inv, leaf, parentLeaf),
                                parentDataProviderRef = parentDataProviderRefTyped(leaf, parentLeaf, inv),
                                // Short label from /api/resourcelocation/resources
                                // (`localizedValues[0].name`) — e.g. "OFC13", "B7".
                                name = inv.name ?: resourceId,
                                // Loop is the parent leaf's name from /api/maps
                                // (PC's "AREA WHITE RIVER" analogue).
                                loopName = leaf?.name ?: parentLeaf?.name,
                                kind = inv.resourceCategoryId?.let { dto.dictionaries.resourceCategories[it] } ?: "site",
                                kindListed = inv.resourceCategoryId?.let { dto.dictionaries.resourceCategories[it] },
                                equipment = inv.allowedEquipment?.let { enrichAllowedEquipment(it, dto.dictionaries) },
                                maxPeople = inv.maxCapacity,
                                photos = photosPayload(inv.photos),
                                sourcePayload =
                                    buildResourceRaw(
                                        inv = inv,
                                        leaf = leaf,
                                        parentLeaf = parentLeaf,
                                        dictionaries = dto.dictionaries,
                                    ),
                            ),
                        ),
                    )
                    emitted++
                }
            }
            log.info(
                "$etlSlug: emitted {} campsites from {} inventory envelopes ({} resources with no matching leaf in /api/maps)",
                emitted,
                dto.inventory.size,
                unmatchedLeaf,
            )
            if (totalRecords != emitted) {
                log.warn("$etlSlug: parsed {} inventory records but emitted {} campsites", totalRecords, emitted)
            }
        }

    private fun campsiteBookingRef(
        inv: ResourceInventory,
        leaf: AspiraLeaf?,
        parentLeaf: AspiraLeaf?,
    ): String? {
        val transactionLocationId = leaf?.transactionLocationId ?: parentLeaf?.transactionLocationId ?: return null
        val mapId = leaf?.mapId ?: inv.firstMapId ?: parentLeaf?.mapId ?: return null
        val resourceLocationId = leaf?.resourceLocationId ?: inv.resourceLocationId ?: parentLeaf?.resourceLocationId ?: return null
        return BookingProviderRef
            .Aspira(
                tenant = aspiraTenant,
                transactionLocationId = transactionLocationId,
                mapId = mapId,
                resourceLocationId = resourceLocationId,
            ).serialize()
    }

    private fun parentDataProviderRefTyped(
        leaf: AspiraLeaf?,
        parentLeaf: AspiraLeaf?,
        inv: ResourceInventory,
    ): DataProviderRef? {
        val parent = leaf ?: parentLeaf
        if (parent != null) {
            return makeParentRef(parent.transactionLocationId, parent.mapId)
        }
        val transactionLocationId = parentLeaf?.transactionLocationId
        val mapId = inv.firstMapId
        return if (transactionLocationId != null && mapId != null) {
            makeParentRef(transactionLocationId, mapId)
        } else {
            null
        }
    }

    private fun makeParentRef(
        transactionLocationId: Long,
        mapId: Long,
    ): DataProviderRef =
        when (parentDataProvider) {
            DataProvider.STRAPI -> DataProviderRef.BcParks(transactionLocationId = transactionLocationId, mapId = mapId)
            else -> DataProviderRef.Aspira(transactionLocationId = transactionLocationId, mapId = mapId)
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
        val photos =
            (obj["photos"] as? JsonArray).orEmpty().mapNotNull { photo ->
                ((photo as? JsonObject)?.get("photoUrlResult") as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            }
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
            photos = photos,
        )
    }

    /**
     * Build the `raw` JSON we persist on the campsite. The per-resource
     * upstream availability array is intentionally *not* stored — that's
     * availability data and lives elsewhere. We keep the catalog signal:
     * who this resource is, how to find its parent campground, and the
     * inventory attributes the booking SPA shows.
     */
    private fun buildResourceRaw(
        inv: ResourceInventory,
        leaf: AspiraLeaf?,
        parentLeaf: AspiraLeaf?,
        dictionaries: AspiraDictionaries,
    ): JsonObject =
        buildJsonObject {
            put("resource_id", inv.resourceId)
            // Parent linkage: prefer leaf metadata when we matched
            // mapIds[0] to a known leaf. Fall back to the
            // resourceLocationId from the inventory record itself so parent
            // refs still resolve whenever the inventory carries enough IDs.
            val parent = leaf ?: parentLeaf
            if (parent != null) {
                put("_parent_aspira_map_id", parent.mapId)
                put("_parent_aspira_txn_loc", parent.transactionLocationId)
                if (parent.resourceLocationId != null) {
                    put("_parent_aspira_resource_loc", parent.resourceLocationId)
                }
                put("_parent_leaf_name", parent.name)
                if (parent.parentName != null) {
                    put("_parent_leaf_parent_name", parent.parentName)
                }
                if (leaf == null && inv.firstMapId != null && inv.firstMapId != parent.mapId) {
                    put("_aspira_resource_map_id", inv.firstMapId)
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
                dictionaries.resourceCategories[inv.resourceCategoryId]?.let {
                    put("resource_category_name", it)
                }
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
                put("allowed_equipment", enrichAllowedEquipment(inv.allowedEquipment, dictionaries))
            }
            if (inv.definedAttributes != null) {
                // Strip Aspira's outer wrapping and resolve the dictionary here, so
                // the row carries `{name, value}` rather than definition ids and enums.
                put("defined_attributes", flattenAttributes(inv.definedAttributes, dictionaries))
            }
        }

    private fun enrichAllowedEquipment(
        equipment: JsonArray,
        dictionaries: AspiraDictionaries,
    ): JsonArray =
        buildJsonArray {
            for (raw in equipment) {
                val item = raw as? JsonObject ?: continue
                val categoryId = item["equipmentCategoryId"]?.jsonPrimitive?.intOrNull
                val subCategoryId = item["subEquipmentCategoryId"]?.jsonPrimitive?.intOrNull
                val label =
                    if (categoryId != null && subCategoryId != null) {
                        dictionaries.equipment[EquipmentKey(categoryId, subCategoryId)]
                    } else {
                        null
                    }
                add(
                    buildJsonObject {
                        categoryId?.let { put("equipment_category_id", it) }
                        subCategoryId?.let { put("sub_equipment_category_id", it) }
                        label?.categoryName?.let { put("equipment_category_name", it) }
                        label?.subCategoryName?.let { put("name", it) }
                    },
                )
            }
        }

    private fun flattenAttributes(
        attrs: JsonArray,
        dictionaries: AspiraDictionaries,
    ): JsonArray =
        buildJsonArray {
            for (raw in attrs) {
                val a = raw as? JsonObject ?: continue
                val definitionId = a["attributeDefinitionId"]?.jsonPrimitive?.intOrNull
                val definition = definitionId?.let { dictionaries.attributes[it] }
                add(
                    buildJsonObject {
                        definitionId?.let { put("definition_id", it) }
                        definition?.name?.let { put("name", it) }
                        attributeValue(a, definition)?.let { put("value", it) }
                    },
                )
            }
        }

    /** The dictionary's labels for an enum value, else the value itself. */
    private fun attributeValue(
        attr: JsonObject,
        definition: AttributeDefinition?,
    ): JsonElement? {
        val labels = attributeValueLabels(attr, definition)
        if (labels.size == 1) return JsonPrimitive(labels.single())
        if (labels.size > 1) {
            return buildJsonArray {
                labels.forEach { add(JsonPrimitive(it)) }
            }
        }

        val value = attr["value"]
        if (value != null && value != JsonNull) return value

        val values = attr["values"] as? JsonArray ?: return null
        if (values.size == 1) return values.single()
        if (values.size > 1) {
            return buildJsonArray { values.forEach { add(it) } }
        }
        return null
    }

    private fun attributeValueLabels(
        attr: JsonObject,
        definition: AttributeDefinition?,
    ): List<String> {
        if (definition == null) return emptyList()
        val scalar = attr["value"]?.jsonPrimitive?.intOrNull
        if (scalar != null) {
            definition.valueLabels[scalar]?.let { return listOf(it) }
        }
        val values = attr["values"] as? JsonArray ?: return emptyList()
        return values.mapNotNull { value ->
            value.jsonPrimitive.intOrNull?.let { definition.valueLabels[it] }
        }
    }

    private fun parseDictionaries(payload: JsonObject?): AspiraDictionaries {
        if (payload == null) return AspiraDictionaries.empty
        val resourceCategories =
            firstJsonArray(
                payload,
                "resource_categories",
                "resourceCategories",
                "resourcecategory",
            )
        return AspiraDictionaries(
            equipment = parseEquipment(payload["equipment"] as? JsonArray),
            resourceCategories = parseResourceCategories(resourceCategories),
            attributes =
                parseAttributes(
                    firstJsonElement(
                        payload,
                        "attributes",
                        "attribute_filterable",
                        "attributeFilterable",
                    ),
                ),
        )
    }

    private fun parseEquipment(equipment: JsonArray?): Map<EquipmentKey, EquipmentLabel> {
        if (equipment == null) return emptyMap()
        val out = mutableMapOf<EquipmentKey, EquipmentLabel>()
        for (rawCategory in equipment) {
            val category = rawCategory as? JsonObject ?: continue
            val categoryId = category["equipmentCategoryId"]?.jsonPrimitive?.intOrNull ?: continue
            val categoryName = localizedLabel(category)
            val subs = category["subEquipmentCategories"] as? JsonArray ?: continue
            for (rawSub in subs) {
                val sub = rawSub as? JsonObject ?: continue
                val subId = sub["subEquipmentCategoryId"]?.jsonPrimitive?.intOrNull ?: continue
                val subName = localizedLabel(sub)
                out[EquipmentKey(categoryId, subId)] =
                    EquipmentLabel(
                        categoryName = categoryName,
                        subCategoryName = subName,
                    )
            }
        }
        return out
    }

    private fun parseResourceCategories(categories: JsonArray?): Map<Int, String> {
        if (categories == null) return emptyMap()
        val out = mutableMapOf<Int, String>()
        for (raw in categories) {
            val category = raw as? JsonObject ?: continue
            val id = category["resourceCategoryId"]?.jsonPrimitive?.intOrNull ?: continue
            val name = localizedLabel(category) ?: continue
            out[id] = name
        }
        return out
    }

    private fun parseAttributes(attributes: JsonElement?): Map<Int, AttributeDefinition> {
        if (attributes == null) return emptyMap()
        val out = mutableMapOf<Int, AttributeDefinition>()
        for (raw in attributeDefinitionElements(attributes)) {
            val attr = raw as? JsonObject ?: continue
            val id = attr["attributeDefinitionId"]?.jsonPrimitive?.intOrNull ?: continue
            out[id] =
                AttributeDefinition(
                    name = localizedLabel(attr),
                    valueLabels = parseAttributeValueLabels(attr["values"] as? JsonArray),
                )
        }
        return out
    }

    private fun attributeDefinitionElements(attributes: JsonElement): List<JsonElement> =
        when (attributes) {
            is JsonArray -> attributes
            is JsonObject -> attributes.values.toList()
            else -> emptyList()
        }

    private fun parseAttributeValueLabels(values: JsonArray?): Map<Int, String> {
        if (values == null) return emptyMap()
        val out = mutableMapOf<Int, String>()
        for (raw in values) {
            val value = raw as? JsonObject ?: continue
            val enumValue = value["enumValue"]?.jsonPrimitive?.intOrNull ?: continue
            val label = localizedLabel(value) ?: continue
            out[enumValue] = label
        }
        return out
    }

    private fun firstJsonElement(
        payload: JsonObject,
        vararg keys: String,
    ): JsonElement? {
        for (key in keys) {
            val element = payload[key]
            if (element != null) return element
        }
        return null
    }

    private fun firstJsonArray(
        payload: JsonObject,
        vararg keys: String,
    ): JsonArray? {
        for (key in keys) {
            val array = payload[key] as? JsonArray
            if (array != null) return array
        }
        return null
    }

    private fun localizedLabel(obj: JsonObject): String? {
        val localized = obj["localizedValues"] as? JsonArray
        val firstLocale = localized?.firstOrNull() as? JsonObject
        return firstLocale?.get("name")?.jsonPrimitive?.contentOrNull
            ?: firstLocale?.get("displayName")?.jsonPrimitive?.contentOrNull
            ?: firstLocale?.get("title")?.jsonPrimitive?.contentOrNull
    }

    /** Parsed shape passed through validate→transform. */
    data class Parsed(
        val inventory: List<Envelope>,
        val maps: JsonArray,
        val dictionaries: AspiraDictionaries,
    )

    data class AspiraDictionaries(
        val equipment: Map<EquipmentKey, EquipmentLabel>,
        val resourceCategories: Map<Int, String>,
        val attributes: Map<Int, AttributeDefinition>,
    ) {
        companion object {
            val empty =
                AspiraDictionaries(
                    equipment = emptyMap(),
                    resourceCategories = emptyMap(),
                    attributes = emptyMap(),
                )
        }
    }

    data class EquipmentKey(
        val categoryId: Int,
        val subCategoryId: Int,
    )

    data class EquipmentLabel(
        val categoryName: String?,
        val subCategoryName: String?,
    )

    data class AttributeDefinition(
        val name: String?,
        val valueLabels: Map<Int, String>,
    )

    /** A single campsite's catalog row, normalized out of Aspira's wrapping. */
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
        val photos: List<String>,
    ) {
        val firstMapId: Long? get() = mapIds.firstOrNull()
    }
}
