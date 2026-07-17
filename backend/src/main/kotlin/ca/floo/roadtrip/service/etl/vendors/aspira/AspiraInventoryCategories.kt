package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decides which Aspira `resourceLocationId`s are non-bookable (parking lots,
 * guided hikes, shuttles, day-use buses) so the POI emitter can drop them —
 * they carry a resourceLocationId and name-match park geometry, but they are
 * not campgrounds.
 *
 * The signal comes straight from the fetched data, not a curated list. Each
 * resource-category dictionary entry carries Aspira's own
 * `showResourceCapacityOnline` flag: `true` for overnight-stay categories
 * (Campsite, Yurt, oTENTik, Backcountry Site, …) and `false` for activity
 * categories. We classify a `resourceLocationId` by the flags of the
 * categories its inventory actually contains.
 *
 * Inputs are the same two captures [AspiraResourcesEtl] reads:
 *   - inventory (`/api/resourcelocation/resources`): a JsonObject keyed by
 *     resourceId; each value carries `resourceLocationId` + `resourceCategoryId`.
 *   - dictionaries (`/api/resourcecategory` …): `resource_categories[]` mapping
 *     `resourceCategoryId` → `showResourceCapacityOnline`.
 *
 * A tenant whose dictionary marks every category bookable (Washington and BC
 * today set the flag `true` across the board) yields an empty set: nothing is
 * dropped, because that tenant's data marks nothing as non-bookable. The ETL
 * represents what each tenant's data actually shows.
 */
object AspiraInventoryCategories {
    /**
     * resourceLocationIds whose inventory is non-empty AND every resource in it
     * belongs to a non-bookable category (`showResourceCapacityOnline: false`).
     *
     * Requiring *all* resources to be non-bookable is the safe direction: a
     * resLoc that mixes campsites with a parking category (e.g. a park HQ that
     * fronts real sites) has a bookable resource, so it is kept. A resource
     * whose category id isn't in the dictionary is treated as bookable (fail
     * open), so an unmapped or new category never silently drops a real
     * campground.
     */
    fun nonBookableResourceLocationIds(
        inventory: List<Envelope>,
        dictionaryPayload: JsonObject?,
    ): Set<Long> {
        val bookableByCategoryId = bookableFlagByCategoryId(dictionaryPayload)
        if (bookableByCategoryId.isEmpty()) return emptySet()

        // resLoc → per-resource "is this a bookable category?" flags.
        val flagsByResLoc = mutableMapOf<Long, MutableList<Boolean>>()
        for (envelope in inventory) {
            val payload = envelope.payload as? JsonObject ?: continue
            for ((_, raw) in payload) {
                val obj = raw as? JsonObject ?: continue
                val resLoc = obj["resourceLocationId"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: continue
                flagsByResLoc.getOrPut(resLoc) { mutableListOf() }.add(isBookableResource(obj, bookableByCategoryId))
            }
        }

        return flagsByResLoc
            .filterValues { flags -> flags.isNotEmpty() && flags.none { it } }
            .keys
    }

    /**
     * dictionary `resource_categories[]` → `resourceCategoryId` →
     * `showResourceCapacityOnline`. Missing flag defaults to bookable (`true`)
     * so an under-specified dictionary never drops sites.
     */
    fun bookableFlagByCategoryId(dictionaryPayload: JsonObject?): Map<Int, Boolean> {
        val categories =
            dictionaryPayload
                ?.get("resource_categories") as? JsonArray
                ?: return emptyMap()
        val out = mutableMapOf<Int, Boolean>()
        for (raw in categories) {
            val category = raw as? JsonObject ?: continue
            val id = category["resourceCategoryId"]?.jsonPrimitive?.intOrNull ?: continue
            out[id] = category["showResourceCapacityOnline"]?.jsonPrimitive?.booleanOrNull ?: true
        }
        return out
    }

    fun isBookableResource(
        resource: JsonObject,
        bookableByCategoryId: Map<Int, Boolean>,
    ): Boolean {
        if (bookableByCategoryId.isEmpty()) return true
        val categoryId = resource["resourceCategoryId"]?.jsonPrimitive?.intOrNull
        return categoryId?.let { bookableByCategoryId[it] } ?: true
    }
}
