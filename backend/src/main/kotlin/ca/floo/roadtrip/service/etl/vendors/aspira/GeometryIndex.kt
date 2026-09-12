package ca.floo.roadtrip.service.etl.vendors.aspira

import org.slf4j.Logger

/**
 * Merges the declared geometry sources into one normalized-name index.
 *
 * Sources are walked in declared order and the first point to claim a
 * normalized name keeps it, so `geometry.sources` order is preference order:
 * campground-level feeds declared before park-polygon centroids win whenever
 * both carry the same name.
 *
 * A declared source that lands nothing is the shape of a format/feed mismatch
 * or upstream drift, so it warns rather than reading as a healthy INFO line.
 */
object GeometryIndex {
    /** Fewer keys than this and the run cannot pin a single leaf; callers fail rather than emit nothing. */
    const val MIN_GEOMETRY_KEYS = 1

    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    fun build(
        sources: List<Pair<String, GeometrySource>>,
        log: Logger,
        etlSlug: String,
    ): Map<String, GeometryPoint> {
        val byName = LinkedHashMap<String, GeometryPoint>()
        for ((slug, source) in sources) {
            val before = byName.size
            val (plottable, dropped) = source.points().partition { it.isOnEarth() }
            for ((key, point) in byNormalizedName(plottable) { it.name }) {
                byName.putIfAbsent(key, GeometryPoint(point.latitude, point.longitude, slug))
            }
            val added = byName.size - before
            log.info(
                "{}: geometry input slug={} contributed {} new keys, dropped {} off-earth points (total={})",
                etlSlug,
                slug,
                added,
                dropped.size,
                byName.size,
            )
            if (added == 0) {
                log.warn(
                    "{}: geometry input slug={} contributed no keys; check its declared format and name_property against the feed",
                    etlSlug,
                    slug,
                )
            }
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

    /**
     * A coordinate the catalog can plot and its JSON codec can encode. `NaN`,
     * `Infinity` and overflowing literals all survive `toDoubleOrNull`, and a
     * single one of them aborts the whole import at JSONB-encode time.
     */
    private fun NamedPoint.isOnEarth(): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in MIN_LATITUDE..MAX_LATITUDE &&
            longitude in MIN_LONGITUDE..MAX_LONGITUDE
}
