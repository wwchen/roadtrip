package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class CampflareCampsiteHomes private constructor(
    private val homeBySiteId: Map<String, String>,
) {
    fun keeps(row: JsonObject): Boolean {
        val siteId = row.stringField(SITE_ID_FIELD) ?: return true
        val home = homeBySiteId[siteId] ?: return true
        return row.stringField(SITE_CAMPGROUND_FIELD) == home
    }

    companion object {
        private const val SITE_ID_FIELD = "id"
        private const val SITE_CAMPGROUND_FIELD = "campground_id"
        private const val SITE_LOOP_FIELD = "loop_name"
        private const val CAMPGROUND_ID_FIELD = "id"
        private const val CAMPGROUND_NAME_FIELD = "name"
        private val nameTokenSeparator = Regex("[^a-z0-9]+")

        fun resolve(
            sites: List<Envelope>,
            campgrounds: List<Envelope>,
        ): CampflareCampsiteHomes {
            val firstCampground = HashMap<String, String>()
            val contested = LinkedHashSet<String>()
            for (row in rows(sites)) {
                val siteId = row.stringField(SITE_ID_FIELD) ?: continue
                val campgroundId = row.stringField(SITE_CAMPGROUND_FIELD) ?: continue
                val first = firstCampground.putIfAbsent(siteId, campgroundId)
                if (first != null && first != campgroundId) contested += siteId
            }
            if (contested.isEmpty()) return CampflareCampsiteHomes(emptyMap())

            val listings = LinkedHashMap<String, MutableList<JsonObject>>()
            for (row in rows(sites)) {
                val siteId = row.stringField(SITE_ID_FIELD) ?: continue
                if (siteId in contested && row.stringField(SITE_CAMPGROUND_FIELD) != null) {
                    listings.getOrPut(siteId) { mutableListOf() } += row
                }
            }
            val wanted = listings.values.flatMap { copies -> copies.mapNotNull { it.stringField(SITE_CAMPGROUND_FIELD) } }.toSet()
            val names = campgroundNames(campgrounds, wanted)
            return CampflareCampsiteHomes(listings.mapValues { (_, copies) -> home(copies, names) })
        }

        private fun home(
            copies: List<JsonObject>,
            names: Map<String, String?>,
        ): String {
            val candidates = copies.mapNotNull { it.stringField(SITE_CAMPGROUND_FIELD) }.distinct()
            val tokens = candidates.associateWith { nameTokens(names[it]) }
            val distinctive =
                candidates.associateWith { candidate ->
                    tokens.getValue(candidate) - candidates.filter { it != candidate }.flatMap { tokens.getValue(it) }.toSet()
                }
            val loop = nameTokens(copies.first().stringField(SITE_LOOP_FIELD))
            val hits = candidates.associateWith { (distinctive.getValue(it) intersect loop).size }
            val best = hits.values.max()
            val leaders = candidates.filter { hits.getValue(it) == best }
            if (best > 0 && leaders.size == 1) return leaders.single()
            val parkOnly = candidates.filter { distinctive.getValue(it).isEmpty() }
            if (parkOnly.size == 1) return parkOnly.single()
            return candidates.first()
        }

        private fun campgroundNames(
            campgrounds: List<Envelope>,
            wanted: Set<String>,
        ): Map<String, String?> {
            val names = HashMap<String, String?>()
            for (row in rows(campgrounds)) {
                val id = row.stringField(CAMPGROUND_ID_FIELD) ?: continue
                if (id in wanted && id !in names) names[id] = row.stringField(CAMPGROUND_NAME_FIELD)
            }
            return names
        }

        private fun nameTokens(name: String?): Set<String> =
            name
                ?.lowercase()
                ?.split(nameTokenSeparator)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                .orEmpty()

        private fun rows(envelopes: List<Envelope>): Sequence<JsonObject> =
            envelopes.asSequence().flatMap { envelope ->
                (envelope.payload as? JsonArray).orEmpty().asSequence().mapNotNull { it as? JsonObject }
            }
    }
}
