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
            for ((key, point) in byNormalizedName(source.points().asIterable()) { it.name }) {
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

    /**
     * The one keying rule: [normalize]d name, blank names dropped, first writer
     * wins. A metadata index joined against this one by matched name is built
     * here too, so the two cannot drift apart.
     */
    fun <T> byNormalizedName(
        rows: Iterable<T>,
        name: (T) -> String,
    ): Map<String, T> {
        val byName = LinkedHashMap<String, T>()
        for (row in rows) {
            val key = normalize(name(row))
            if (key.isNotEmpty()) byName.putIfAbsent(key, row)
        }
        return byName
    }
}
