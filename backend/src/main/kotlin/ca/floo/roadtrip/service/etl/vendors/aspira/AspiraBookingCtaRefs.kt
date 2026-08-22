package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class AspiraBookingCtaRef(
    val mapId: Long,
    val resourceLocationId: Long,
)

/**
 * Which map node a campground POI's "Book" link should open, shared by the
 * Aspira (PC/WA) and BC Parks campground ETLs.
 *
 * A leaf's own `mapId` is the POI's identity but not always a booking grid:
 * some leaves are container nodes whose sites all live on one child map. Where
 * the inventory shows a single child, that child is the better deep link. Where
 * it shows several — Sasquatch's Hicks/Bench/Lakeside/Group maps all sit under
 * one `resourceLocationId` — no child covers the POI, so the container stays.
 */
internal object AspiraBookingCtaRefs {
    /** `resourceLocationId` → the distinct maps its bookable resources sit on. */
    fun bookableMapIdsByResourceLocationId(
        inventory: List<Envelope>,
        dictionaryPayload: JsonObject?,
    ): Map<Long, Set<Long>> {
        val bookableByCategoryId = AspiraInventoryCategories.bookableFlagByCategoryId(dictionaryPayload)
        val mapIds = mutableMapOf<Long, MutableSet<Long>>()
        for (envelope in inventory) {
            val payload = envelope.payload as? JsonObject ?: continue
            for ((_, raw) in payload) {
                val obj = raw as? JsonObject ?: continue
                if (!AspiraInventoryCategories.isBookableResource(obj, bookableByCategoryId)) continue
                val resourceLocationId = obj.longValue("resourceLocationId") ?: continue
                mapIds.getOrPut(resourceLocationId) { mutableSetOf() } += obj.mapIds()
            }
        }
        return mapIds
    }

    /** Null when the leaf has no `resourceLocationId` or no bookable inventory under it. */
    fun forLeaf(
        leaf: AspiraLeaf,
        bookableMapIds: Map<Long, Set<Long>>,
    ): AspiraBookingCtaRef? {
        val resourceLocationId = leaf.resourceLocationId ?: return null
        val mapIds = bookableMapIds[resourceLocationId]?.takeIf { it.isNotEmpty() } ?: return null
        return AspiraBookingCtaRef(
            mapId = mapIds.singleOrNull() ?: leaf.mapId,
            resourceLocationId = resourceLocationId,
        )
    }

    private fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.mapIds(): List<Long> = (this["mapIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull } ?: emptyList()
}
