package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.domain.BookingProvider
import ca.floo.roadtrip.model.domain.BookingRef
import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.DataProvider
import ca.floo.roadtrip.model.etl.CampgroundCampsiteEtlOutput
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.CampgroundCampsiteEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraBookingCtaRef
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraInventoryCategories
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeavesWalk
import ca.floo.roadtrip.service.etl.vendors.aspira.jaccard
import ca.floo.roadtrip.service.etl.vendors.aspira.normalize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Merge ETL for BC Parks: joins Aspira booking data with BC Parks Strapi
 * metadata to produce both campgrounds (enriched with Strapi description,
 * photos, contact, URL) and campsites (from Aspira per-park inventory).
 *
 * This replaces the separate `aspira-bc-pins` (campground) and
 * `aspira-bc-resources` (campsite) ETLs with a single pass that co-produces
 * both, ensuring consistent parent references.
 *
 * Inputs (declared in YAML):
 *   - `aspira-leaves-bc` (etl): intermediate leaves payload from AspiraLeavesEtl
 *   - `bcparks-strapi` (data_source): Strapi pages for geometry + rich metadata
 *   - `aspira-inventory-bc` (data_source): per-park `/api/resourcelocation/resources`
 *   - `aspira-dictionaries-bc` (data_source): `/api/resourcecategory` + `/api/equipment`
 *   - `aspira-maps-bc` (data_source): `/api/maps` tree for campsite loop labeling
 */
class BcParksMergeEtl(
    override val etlSlug: String = "bcparks-merge",
) : CampgroundCampsiteEtl<BcParksMergeDto> {
    private val log = LoggerFactory.getLogger(javaClass)
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): BcParksMergeDto {
        val slugs = inputs.dataSourceSlugs()
        val mapsSlug = slugs.first { it.contains("maps") }
        val strapiSlug = slugs.first { it.contains("bcparks") || it.contains("strapi") }
        val inventorySlug = slugs.first { it.contains("inventory") }
        val dictionarySlug = slugs.firstOrNull { it.contains("dictionaries") }

        val mapsArray = inputs.envelope(mapsSlug).payload.jsonArray
        val leaves = AspiraLeavesWalk.walk(mapsArray)
        val strapiEnvelopes = inputs.envelopes(strapiSlug)
        val inventoryEnvelopes = inputs.envelopes(inventorySlug)
        val dictionaryPayload = dictionarySlug?.let { inputs.envelope(it).payload as? JsonObject }

        val strapiRows = parseStrapiRows(strapiEnvelopes)

        return BcParksMergeDto(
            leaves = leaves,
            strapiRows = strapiRows,
            strapiEnvelopes = strapiEnvelopes,
            inventoryEnvelopes = inventoryEnvelopes,
            dictionaryPayload = dictionaryPayload,
            mapsArray = mapsArray,
        )
    }

    override fun validate(dto: BcParksMergeDto): ValidationResult<BcParksMergeDto> {
        val errs = mutableListOf<String>()
        if (dto.leaves.isEmpty()) errs += "no Aspira leaves from /api/maps"
        if (dto.strapiRows.isEmpty()) errs += "no BC Parks Strapi rows parsed"
        if (dto.inventoryEnvelopes.isEmpty()) errs += "no inventory envelopes"
        return if (errs.isEmpty()) ValidationResult.Ok(dto) else ValidationResult.Bad(null, errs)
    }

    override fun transform(
        dto: BcParksMergeDto,
        ctx: TransformCtx,
    ): CampgroundCampsiteEtlOutput {
        val host = ctx.argFor(etlSlug, "host") ?: error("$etlSlug: missing args.host")
        val subcategory = ctx.subcategoryFor(etlSlug)
        val agency = ctx.requiredConstantAgency(etlSlug)

        val campgrounds = transformCampgrounds(dto, host, subcategory, agency)
        val campsites = transformCampsites(dto)

        return CampgroundCampsiteEtlOutput(campgrounds = campgrounds, campsites = campsites)
    }

    private fun transformCampgrounds(
        dto: BcParksMergeDto,
        host: String,
        subcategory: String?,
        agency: String,
    ): List<CampgroundUpsertCandidate> {
        val nonBookableResLocs =
            AspiraInventoryCategories.nonBookableResourceLocationIds(
                inventory = dto.inventoryEnvelopes,
                dictionaryPayload = dto.dictionaryPayload,
            )
        val bookingCtaRefsByResourceLocationId =
            canonicalBookingCtaRefs(dto.inventoryEnvelopes, dto.dictionaryPayload)

        val byName = LinkedHashMap<String, StrapiMatch>()
        for (row in dto.strapiRows) {
            val key = normalize(row.name)
            if (key.isNotEmpty()) byName.putIfAbsent(key, StrapiMatch(row.lat, row.lon, row))
        }

        val tokenIndex: List<Pair<Set<String>, StrapiMatch>> =
            byName.entries.map { (k, v) -> k.split(' ').toSet() to v }

        val campgrounds = mutableListOf<CampgroundUpsertCandidate>()
        var exact = 0
        var fuzzy = 0
        var viaParent = 0
        var miss = 0
        var skippedContainer = 0
        var skippedNonBookable = 0

        for (leaf in dto.leaves) {
            if (leaf.resourceLocationId == null) {
                skippedContainer++
                continue
            }
            if (leaf.resourceLocationId in nonBookableResLocs) {
                skippedNonBookable++
                continue
            }

            val nk = normalize(leaf.name)
            var match: StrapiMatch? = byName[nk]
            var matchKind = "exact"

            if (match == null) {
                val ntoks = nk.split(' ').toSet()
                val best = tokenIndex.maxByOrNull { jaccard(it.first, ntoks) }
                val score = best?.let { jaccard(it.first, ntoks) } ?: 0.0
                if (best != null && score >= FUZZY_THRESHOLD) {
                    match = best.second
                    matchKind = "fuzzy"
                }
            }

            if (match == null && leaf.parentName != null) {
                val pk = normalize(leaf.parentName)
                match = byName[pk]
                if (match != null) matchKind = "parent"
            }

            if (match == null) {
                miss++
                continue
            }

            when (matchKind) {
                "exact" -> exact++
                "fuzzy" -> fuzzy++
                "parent" -> viaParent++
            }

            val dataRef = aspiraDataProviderRef(leaf.transactionLocationId, leaf.mapId)
            val bookingCtaRef = leaf.resourceLocationId.let { bookingCtaRefsByResourceLocationId[it] }
            val strapiRow = match.strapiRow

            campgrounds +=
                CampgroundUpsertCandidate(
                    dataProvider = DataProvider.ASPIRA,
                    dataProviderRef = dataRef,
                    bookingProvider = BookingProvider.ASPIRA,
                    bookingProviderRef = bookingCtaRef?.let { campgroundBookingRef(leaf, it) },
                    name = leaf.name,
                    latitude = match.lat,
                    longitude = match.lon,
                    kind = subcategory,
                    mediumDescription = strapiRow.description,
                    location = locationPayload(match.lat, match.lon),
                    reservationUrl = "https://$host/",
                    links = linksPayload("https://$host/", strapiRow.url),
                    photos = strapiRow.photoUrl?.let(::photoPayload),
                    management = managementPayload(agency),
                    contact = strapiRow.phone?.let(::contactPayload),
                    metadata = metadataPayload(leaf, host, matchKind, bookingCtaRef, strapiRow),
                    sourceUrl = "https://$host/",
                    sourcePayload = sourcePayload(leaf, matchKind, bookingCtaRef, strapiRow),
                )
        }

        log.info(
            "$etlSlug: {} leaves → {} campgrounds " +
                "(exact={} fuzzy={} parent={} miss={} skippedContainer={} skippedNonBookable={})",
            dto.leaves.size,
            campgrounds.size,
            exact,
            fuzzy,
            viaParent,
            miss,
            skippedContainer,
            skippedNonBookable,
        )
        return campgrounds
    }

    private fun transformCampsites(dto: BcParksMergeDto): List<CampsiteUpsertCandidate> {
        val leaves = AspiraLeavesWalk.walk(dto.mapsArray)
        val leavesByMapId = leaves.associateBy { it.mapId }
        val leavesByResourceLocationId =
            leaves
                .mapNotNull { leaf -> leaf.resourceLocationId?.let { it to leaf } }
                .toMap()

        val dictionaries = parseDictionaries(dto.dictionaryPayload)
        val out = mutableListOf<CampsiteUpsertCandidate>()
        var unmatchedLeaf = 0

        for (envelope in dto.inventoryEnvelopes) {
            val payload = envelope.payload as? JsonObject ?: continue
            for ((resourceId, raw) in payload) {
                if (resourceId.isEmpty()) continue
                val obj = raw as? JsonObject ?: continue
                val inv = parseResourceInventory(resourceId, obj) ?: continue
                val leafMapId = inv.firstMapId
                val leaf = leafMapId?.let { leavesByMapId[it] }
                val parentLeaf = leaf ?: inv.resourceLocationId?.let { leavesByResourceLocationId[it] }
                if (leafMapId != null && leaf == null) unmatchedLeaf++
                out +=
                    CampsiteUpsertCandidate(
                        dataProvider = DataProvider.ASPIRA,
                        dataProviderRef = resourceId,
                        bookingProvider = BookingProvider.ASPIRA,
                        bookingProviderRef = campsiteBookingRef(inv, leaf, parentLeaf),
                        parentDataProvider = DataProvider.ASPIRA,
                        parentDataProviderRef = parentDataProviderRef(leaf, parentLeaf, inv),
                        name = inv.name ?: resourceId,
                        loopName = leaf?.name ?: parentLeaf?.name,
                        kind = inv.resourceCategoryId?.let { dictionaries.resourceCategories[it] } ?: "site",
                        kindListed = inv.resourceCategoryId?.let { dictionaries.resourceCategories[it] },
                        equipment = inv.allowedEquipment?.let { enrichAllowedEquipment(it, dictionaries) },
                        maxPeople = inv.maxCapacity,
                        sourcePayload = buildResourceRaw(inv, leaf, parentLeaf, dictionaries),
                    )
            }
        }

        log.info(
            "$etlSlug: emitted {} campsites from {} inventory envelopes ({} with no matching leaf)",
            out.size,
            dto.inventoryEnvelopes.size,
            unmatchedLeaf,
        )
        return out
    }

    // ---- Campground helpers ---------------------------------------------------

    private fun campgroundBookingRef(
        leaf: AspiraLeaf,
        bookingCtaRef: AspiraBookingCtaRef,
    ): String =
        BookingRef
            .Aspira(
                tenant = ASPIRA_TENANT,
                transactionLocationId = leaf.transactionLocationId,
                mapId = bookingCtaRef.mapId,
                resourceLocationId = bookingCtaRef.resourceLocationId,
            ).serialize()

    private fun canonicalBookingCtaRefs(
        inventory: List<Envelope>,
        dictionaryPayload: JsonObject?,
    ): Map<Long, AspiraBookingCtaRef> {
        val bookableByCategoryId = AspiraInventoryCategories.bookableFlagByCategoryId(dictionaryPayload)
        val refs = mutableMapOf<Long, AspiraBookingCtaRef>()
        for (envelope in inventory) {
            val payload = envelope.payload as? JsonObject ?: continue
            for ((_, raw) in payload) {
                val obj = raw as? JsonObject ?: continue
                if (!AspiraInventoryCategories.isBookableResource(obj, bookableByCategoryId)) continue
                val resourceLocationId = obj["resourceLocationId"]?.jsonPrimitive?.longOrNull ?: continue
                val mapId = mapIds(obj).minOrNull() ?: continue
                val current = refs[resourceLocationId]
                if (current == null || mapId < current.mapId) {
                    refs[resourceLocationId] = AspiraBookingCtaRef(mapId = mapId, resourceLocationId = resourceLocationId)
                }
            }
        }
        return refs
    }

    private fun locationPayload(
        latitude: Double,
        longitude: Double,
    ): JsonObject =
        buildJsonObject {
            put("latitude", latitude)
            put("longitude", longitude)
            put("region", REGION)
            put("country", COUNTRY)
        }

    private fun linksPayload(
        bookingUrl: String,
        infoUrl: String?,
    ): JsonArray =
        buildJsonArray {
            add(buildJsonObject { put("url", bookingUrl) })
            if (infoUrl != null && infoUrl != bookingUrl) {
                add(buildJsonObject { put("url", infoUrl) })
            }
        }

    private fun photoPayload(url: String): JsonArray =
        buildJsonArray {
            add(buildJsonObject { put("url", url) })
        }

    private fun managementPayload(agency: String): JsonObject = buildJsonObject { put("agency", agency) }

    private fun contactPayload(phone: String): JsonObject = buildJsonObject { put("phone", phone) }

    private fun metadataPayload(
        leaf: AspiraLeaf,
        host: String,
        matchKind: String,
        bookingCtaRef: AspiraBookingCtaRef?,
        strapiRow: BcParksStrapiRow,
    ): JsonObject =
        buildJsonObject {
            put("host", host)
            put("transaction_location_id", leaf.transactionLocationId)
            put("map_id", leaf.mapId)
            leaf.resourceLocationId?.let { put("resource_location_id", it) }
            leaf.parentName?.let { put("parent_name", it) }
            put("match_kind", matchKind)
            strapiRow.orcs?.let { put("strapi_orcs", it) }
            bookingCtaRef?.let {
                put(
                    "booking_cta_provider_ref",
                    buildJsonObject {
                        put("transactionLocationId", leaf.transactionLocationId)
                        put("mapId", it.mapId)
                        put("resourceLocationId", it.resourceLocationId)
                    },
                )
            }
        }

    private fun sourcePayload(
        leaf: AspiraLeaf,
        matchKind: String,
        bookingCtaRef: AspiraBookingCtaRef?,
        strapiRow: BcParksStrapiRow,
    ): JsonObject =
        buildJsonObject {
            put("name", leaf.name)
            put("transactionLocationId", leaf.transactionLocationId)
            put("mapId", leaf.mapId)
            leaf.resourceLocationId?.let { put("resourceLocationId", it) }
            leaf.parentName?.let { put("parent_name", it) }
            put("match_kind", matchKind)
            strapiRow.orcs?.let { put("strapi_orcs", it) }
            strapiRow.url?.let { put("strapi_url", it) }
            bookingCtaRef?.let {
                put(
                    "booking_cta_provider_ref",
                    buildJsonObject {
                        put("transactionLocationId", leaf.transactionLocationId)
                        put("mapId", it.mapId)
                        put("resourceLocationId", it.resourceLocationId)
                    },
                )
            }
        }

    // ---- Campsite helpers -----------------------------------------------------

    private fun campsiteBookingRef(
        inv: ResourceInventory,
        leaf: AspiraLeaf?,
        parentLeaf: AspiraLeaf?,
    ): String? {
        val transactionLocationId = leaf?.transactionLocationId ?: parentLeaf?.transactionLocationId ?: return null
        val mapId = leaf?.mapId ?: inv.firstMapId ?: parentLeaf?.mapId ?: return null
        val resourceLocationId = leaf?.resourceLocationId ?: inv.resourceLocationId ?: parentLeaf?.resourceLocationId ?: return null
        return BookingRef
            .Aspira(
                tenant = ASPIRA_TENANT,
                transactionLocationId = transactionLocationId,
                mapId = mapId,
                resourceLocationId = resourceLocationId,
            ).serialize()
    }

    private fun parentDataProviderRef(
        leaf: AspiraLeaf?,
        parentLeaf: AspiraLeaf?,
        inv: ResourceInventory,
    ): String? {
        val parent = leaf ?: parentLeaf
        if (parent != null) {
            return aspiraDataProviderRef(parent.transactionLocationId, parent.mapId)
        }
        val transactionLocationId = parentLeaf?.transactionLocationId
        val mapId = inv.firstMapId
        return if (transactionLocationId != null && mapId != null) {
            aspiraDataProviderRef(transactionLocationId, mapId)
        } else {
            null
        }
    }

    private fun parseResourceInventory(
        resourceId: String,
        obj: JsonObject,
    ): ResourceInventory? {
        val localized = obj["localizedValues"] as? JsonArray
        val firstLocale = localized?.firstOrNull() as? JsonObject
        val name = firstLocale?.get("name")?.jsonPrimitive?.contentOrNull
        val resourceCategoryId = obj["resourceCategoryId"]?.jsonPrimitive?.intOrNull
        val resourceLocationId = obj["resourceLocationId"]?.jsonPrimitive?.long
        val maxCapacity = obj["maxCapacity"]?.jsonPrimitive?.intOrNull
        val allowedEquipment = obj["allowedEquipment"] as? JsonArray
        val mapIdsArr =
            (obj["mapIds"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.long.takeIf { _ -> it.jsonPrimitive.contentOrNull != null } }
                ?: emptyList()
        return ResourceInventory(
            resourceId = resourceId,
            name = name,
            resourceCategoryId = resourceCategoryId,
            resourceLocationId = resourceLocationId,
            maxCapacity = maxCapacity,
            allowedEquipment = allowedEquipment,
            mapIds = mapIdsArr,
        )
    }

    private fun buildResourceRaw(
        inv: ResourceInventory,
        leaf: AspiraLeaf?,
        parentLeaf: AspiraLeaf?,
        dictionaries: Dictionaries,
    ): JsonObject =
        buildJsonObject {
            put("resource_id", inv.resourceId)
            val parent = leaf ?: parentLeaf
            if (parent != null) {
                put("_parent_aspira_map_id", parent.mapId)
                put("_parent_aspira_txn_loc", parent.transactionLocationId)
                parent.resourceLocationId?.let { put("_parent_aspira_resource_loc", it) }
                put("_parent_leaf_name", parent.name)
                parent.parentName?.let { put("_parent_leaf_parent_name", it) }
                if (leaf == null && inv.firstMapId != null && inv.firstMapId != parent.mapId) {
                    put("_aspira_resource_map_id", inv.firstMapId)
                }
            } else {
                inv.firstMapId?.let { put("_parent_aspira_map_id", it) }
                inv.resourceLocationId?.let { put("_parent_aspira_resource_loc", it) }
            }
            inv.resourceCategoryId?.let { id ->
                put("resource_category_id", id)
                dictionaries.resourceCategories[id]?.let { put("resource_category_name", it) }
            }
            inv.maxCapacity?.let { put("max_capacity", it) }
            inv.allowedEquipment?.let { put("allowed_equipment", enrichAllowedEquipment(it, dictionaries)) }
        }

    private fun enrichAllowedEquipment(
        equipment: JsonArray,
        dictionaries: Dictionaries,
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

    // ---- Strapi parsing -------------------------------------------------------

    private fun parseStrapiRows(envelopes: List<Envelope>): List<BcParksStrapiRow> {
        val rows = mutableListOf<BcParksStrapiRow>()
        for (env in envelopes) {
            val data = env.payload.jsonObject["data"]?.jsonArray ?: continue
            for (row in data) {
                val o = row.jsonObject
                val name = o["protectedAreaName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val lat = o["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val lon = o["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val orcs = o["orcs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val description =
                    o["description"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                val phone =
                    o["parkContact"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                val photoUrl = extractPhotoUrl(o["parkPhotos"] as? JsonArray)
                rows +=
                    BcParksStrapiRow(
                        name = name,
                        lat = lat,
                        lon = lon,
                        orcs = orcs,
                        url = url,
                        description = description,
                        phone = phone,
                        photoUrl = photoUrl,
                    )
            }
        }
        return rows
    }

    private fun extractPhotoUrl(photos: JsonArray?): String? {
        if (photos == null) return null
        val candidates =
            photos.mapNotNull { raw ->
                val p = raw as? JsonObject ?: return@mapNotNull null
                val url = p["imageUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val isActive = p["isActive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val isFeatured = p["isFeatured"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val sortOrder = p["sortOrder"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE
                if (!isActive) return@mapNotNull null
                Triple(url, isFeatured, sortOrder)
            }
        return candidates
            .sortedWith(compareByDescending<Triple<String, Boolean, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()
            ?.first
    }

    // ---- Dictionary parsing ---------------------------------------------------

    private fun parseDictionaries(payload: JsonObject?): Dictionaries {
        if (payload == null) return Dictionaries.empty
        val categories = payload["resource_categories"] as? JsonArray
        return Dictionaries(
            equipment = parseEquipment(payload["equipment"] as? JsonArray),
            resourceCategories = parseResourceCategories(categories),
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
                out[EquipmentKey(categoryId, subId)] = EquipmentLabel(categoryName, subName)
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

    private fun localizedLabel(obj: JsonObject): String? {
        val localized = obj["localizedValues"] as? JsonArray
        val firstLocale = localized?.firstOrNull() as? JsonObject
        return firstLocale?.get("name")?.jsonPrimitive?.contentOrNull
            ?: firstLocale?.get("displayName")?.jsonPrimitive?.contentOrNull
    }

    private fun mapIds(obj: JsonObject): List<Long> =
        (obj["mapIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull } ?: emptyList()

    private companion object {
        const val FUZZY_THRESHOLD = 0.5
        const val ASPIRA_DATA_REF_PREFIX = "aspira-"
        const val ASPIRA_TENANT = "bc"
        const val REGION = "BC"
        const val COUNTRY = "CA"

        fun aspiraDataProviderRef(
            transactionLocationId: Long,
            mapId: Long,
        ): String = "$ASPIRA_DATA_REF_PREFIX$transactionLocationId-$mapId"
    }

    private data class StrapiMatch(
        val lat: Double,
        val lon: Double,
        val strapiRow: BcParksStrapiRow,
    )

    private data class ResourceInventory(
        val resourceId: String,
        val name: String?,
        val resourceCategoryId: Int?,
        val resourceLocationId: Long?,
        val maxCapacity: Int?,
        val allowedEquipment: JsonArray?,
        val mapIds: List<Long>,
    ) {
        val firstMapId: Long? get() = mapIds.firstOrNull()
    }

    private data class EquipmentKey(
        val categoryId: Int,
        val subCategoryId: Int,
    )

    private data class EquipmentLabel(
        val categoryName: String?,
        val subCategoryName: String?,
    )

    private data class Dictionaries(
        val equipment: Map<EquipmentKey, EquipmentLabel>,
        val resourceCategories: Map<Int, String>,
    ) {
        companion object {
            val empty = Dictionaries(emptyMap(), emptyMap())
        }
    }
}
