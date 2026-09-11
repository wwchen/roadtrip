package ca.floo.roadtrip.service.etl.vendors.aspira

import org.slf4j.Logger

/**
 * Merges the declared geometry sources into one normalized-name index.
 *
 * Sources are walked in declared order and the first point to claim a
 * normalized name keeps it, so `geometry.sources` order is preference order:
 * campground-level feeds declared before park-polygon centroids win whenever
 * both carry the same name.
 */
object GeometryIndex {
    fun build(
        sources: List<Pair<String, GeometrySource>>,
        log: Logger,
        etlSlug: String,
    ): Map<String, GeometryPoint> {
        val byName = LinkedHashMap<String, GeometryPoint>()
        for ((slug, source) in sources) {
            val before = byName.size
            for (point in source.points()) {
                val key = normalize(point.name)
                if (key.isEmpty()) continue
                byName.putIfAbsent(key, GeometryPoint(point.latitude, point.longitude, slug))
            }
            log.info(
                "{}: geometry input slug={} contributed {} new keys (total={})",
                etlSlug,
                slug,
                byName.size - before,
                byName.size,
            )
        }
        return byName
    }
}
