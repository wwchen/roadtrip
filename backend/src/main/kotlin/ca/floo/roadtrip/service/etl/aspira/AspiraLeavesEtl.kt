package ca.floo.roadtrip.service.etl.aspira

import ca.floo.roadtrip.models.ValidationResult
import ca.floo.roadtrip.service.etl.InputBundle
import ca.floo.roadtrip.service.etl.SourceEtl
import ca.floo.roadtrip.service.etl.TransformCtx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Aspira /api/maps tree → flat list of bookable leaves.
//
// `/api/maps` returns a tree of map "nodes". Each node is one screen the
// booking SPA can render — a region overview, a campground site, a
// retreat-center building. The leaves we care about are the ones with
// `transactionLocationId` set: those are the records the deeplink builder
// keys off (RFC 0006). Internal nodes have null txnLoc and just steer
// navigation.
//
// Title resolution: the leaf's own `localizedValues` is the preferred
// source ("Tunnel Mountain Village I", "Cedar Spring and Christian
// Beach"). Some tenants (PC) leave that empty on the leaf itself and
// only fill it on the parent map's `mapLinks` entry pointing at the
// leaf — so we fall back to that. Culture filter is `startsWith("en")`,
// not equality, because PC uses "en-CA" and WA uses "en-US".
//
// One ETL class, three registry entries (WA / BC / PC). The host is
// implicit — every leaf this ETL emits belongs to the tenant whose
// /api/maps capture it parsed.
class AspiraLeavesEtl(
    override val etlSlug: String,
) : SourceEtl<AspiraMapsDto, AspiraLeavesPayload> {
    override fun parse(inputs: InputBundle): AspiraMapsDto {
        val envelope = inputs.soleEnvelopes().single()
        val maps = envelope.payload.jsonArray
        return AspiraMapsDto(maps = maps)
    }

    override fun validate(dto: AspiraMapsDto): ValidationResult<AspiraMapsDto> =
        if (dto.maps.isEmpty()) {
            ValidationResult.Bad(null, listOf("$etlSlug: empty /api/maps payload"))
        } else {
            ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: AspiraMapsDto,
        ctx: TransformCtx,
    ): AspiraLeavesPayload {
        val byId =
            dto.maps.associate { node ->
                val obj = node.jsonObject
                val id =
                    obj["mapId"]?.jsonPrimitive?.long
                        ?: error("$etlSlug: map node without mapId: $obj")
                id to obj
            }

        val seen = mutableSetOf<Long>()
        val leaves = mutableListOf<AspiraLeaf>()

        for (node in dto.maps) {
            val obj = node.jsonObject
            val txnLoc = obj["transactionLocationId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (txnLoc == null) continue

            val mapId = obj["mapId"]!!.jsonPrimitive.long
            if (mapId in seen) continue

            val title = titleFor(obj, byId) ?: continue
            val resLoc = obj["resourceLocationId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val parentName = parentNameFor(obj, byId)

            leaves +=
                AspiraLeaf(
                    name = title,
                    transactionLocationId = txnLoc,
                    mapId = mapId,
                    resourceLocationId = resLoc,
                    parentName = parentName,
                )
            seen += mapId
        }

        // Some maps only declare a leaf indirectly via a parent's mapLinks
        // entry (childMapId points at a node not in the array, but the link
        // itself carries the txnLoc). Catch those too.
        for (node in dto.maps) {
            for (link in node.jsonObject["mapLinks"]?.jsonArray.orEmpty()) {
                val l = link.jsonObject
                val txnLoc = l["transactionLocationId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: continue
                val childMapId = l["childMapId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: continue
                if (childMapId in seen) continue
                val title = localizedTitle(l["localizations"]?.jsonArray) ?: continue
                val resLoc = l["resourceLocationId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                val parentName = localizedTitle(node.jsonObject["localizedValues"]?.jsonArray)

                leaves +=
                    AspiraLeaf(
                        name = title,
                        transactionLocationId = txnLoc,
                        mapId = childMapId,
                        resourceLocationId = resLoc,
                        parentName = parentName,
                    )
                seen += childMapId
            }
        }

        return AspiraLeavesPayload(slug = etlSlug, leaves = leaves)
    }

    /**
     * Prefer the node's own en-* localizedValues title. Fall back to the
     * parent map's mapLinks localizations (PC tenant routinely leaves the
     * node title blank).
     */
    private fun titleFor(
        node: JsonObject,
        byId: Map<Long, JsonObject>,
    ): String? {
        val direct = localizedTitle(node["localizedValues"]?.jsonArray)
        if (direct != null) return direct
        val parentId =
            node["parentMap"]
                ?.jsonObject
                ?.get("mapId")
                ?.jsonPrimitive
                ?.long ?: return null
        val parent = byId[parentId] ?: return null
        val ourId = node["mapId"]!!.jsonPrimitive.long
        for (link in parent["mapLinks"]?.jsonArray.orEmpty()) {
            val l = link.jsonObject
            if (l["childMapId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() == ourId) {
                return localizedTitle(l["localizations"]?.jsonArray)
            }
        }
        return null
    }

    /** Walk one parent up and return its title (used by the join-by-name fallback). */
    private fun parentNameFor(
        node: JsonObject,
        byId: Map<Long, JsonObject>,
    ): String? {
        val parentId =
            node["parentMap"]
                ?.jsonObject
                ?.get("mapId")
                ?.jsonPrimitive
                ?.long ?: return null
        val parent = byId[parentId] ?: return null
        return localizedTitle(parent["localizedValues"]?.jsonArray)
    }

    /**
     * Pull the first en-* title out of a localizations / localizedValues
     * array. Each entry shape: { cultureName: "en-US", title: "..." }.
     * Tenant cultures vary (en-US for WA, en-CA for PC); prefix-match.
     */
    private fun localizedTitle(arr: JsonArray?): String? {
        if (arr == null) return null
        for (entry in arr) {
            val o = entry.jsonObject
            val culture = o["cultureName"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!culture.startsWith("en", ignoreCase = true)) continue
            val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (title != null) return title
        }
        return null
    }

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }
    }
}

/** Just-parsed envelope, before we walk the tree. */
data class AspiraMapsDto(
    val maps: JsonArray,
) {
    val isEmpty: Boolean get() = maps.isEmpty()
}

/** Materialized intermediate output. Downstream join-by-name ETL consumes this. */
@Serializable
data class AspiraLeavesPayload(
    val slug: String,
    val leaves: List<AspiraLeaf>,
)

@Serializable
data class AspiraLeaf(
    val name: String,
    @kotlinx.serialization.SerialName("transaction_location_id") val transactionLocationId: Long,
    @kotlinx.serialization.SerialName("map_id") val mapId: Long,
    @kotlinx.serialization.SerialName("resource_location_id") val resourceLocationId: Long? = null,
    /** Title of the parent map node, when the leaf is a sub-area (PC backcountry). */
    @kotlinx.serialization.SerialName("parent_name") val parentName: String? = null,
)
