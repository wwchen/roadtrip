package ca.floo.roadtrip.service.etl.aspira

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Walk an Aspira `/api/maps` tree and return the bookable leaves.
 *
 * A leaf is any node with a non-null `transactionLocationId` — those
 * are the records the deeplink builder keys off (RFC 0006). Internal
 * navigation nodes have null txnLoc and are skipped.
 *
 * Two callers today:
 *   - [AspiraLeavesEtl] (intermediate ETL: maps → leaves payload for the
 *     join-by-name campground emitter).
 *   - [AspiraResourcesEtl] (reservable_data terminal: needs leaf names
 *     and parent context to label resources).
 *
 * Both read the same `/api/maps` raw capture, so the walk lives here as
 * a pure helper. Cross-row etl refs are not supported by the
 * orchestrator (RFC 0008), so duplicating the walk in each consumer is
 * the right shape — but the walk itself is shared code.
 */
object AspiraLeavesWalk {
    /**
     * Pure walk. Input is the `/api/maps` JSON array (the captured
     * envelope's `payload` field). Output is one [AspiraLeaf] per
     * bookable node.
     *
     * Title resolution: prefer the node's own en-* `localizedValues`
     * title. Fall back to the parent map's `mapLinks` localizations
     * (PC tenant routinely leaves the node title blank). Culture filter
     * is prefix-match on `startsWith("en")` — PC uses "en-CA", WA uses
     * "en-US".
     *
     * Two-pass walk:
     *   1. Visit every node directly. Skip ones with no txnLoc, no
     *      title, or already-seen mapId.
     *   2. Visit every parent's `mapLinks` array. Some maps declare a
     *      leaf only indirectly via a parent's mapLinks entry
     *      (childMapId points at a node not in the array, but the link
     *      itself carries the txnLoc). Catch those too.
     */
    fun walk(maps: JsonArray): List<AspiraLeaf> {
        val byId =
            maps.associate { node ->
                val obj = node.jsonObject
                val id =
                    obj["mapId"]?.jsonPrimitive?.long
                        ?: error("aspira maps walk: node without mapId: $obj")
                id to obj
            }

        val seen = mutableSetOf<Long>()
        val leaves = mutableListOf<AspiraLeaf>()

        // First pass: direct nodes.
        for (node in maps) {
            val obj = node.jsonObject
            val txnLoc = obj["transactionLocationId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: continue
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

        // Second pass: leaves only declared via parent.mapLinks entries.
        for (node in maps) {
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

        return leaves
    }

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
}
