package ca.floo.roadtrip.repo

import org.jooq.DSLContext

data class Bbox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

// Slim row shape for the bbox endpoint. Just enough for MapLibre to place
// + color a pin: id, lat/lng, category for color band, subcategory for the
// campground sub-bucket. Everything richer lives behind GET /api/pois/{id}.
internal data class PoiRow(
    val id: Long,
    val category: String,
    val subcategory: String?,
    val lng: Double,
    val lat: Double,
)

// Wide row shape returned by GET /api/pois/{id}. Same projection the bbox
// endpoint used to ship for every row; now paid for only on pin click.
internal data class PoiDetailRow(
    val id: Long,
    val source: String,
    val sourceId: String,
    val category: String,
    val subcategory: String?,
    val name: String,
    val region: String?,
    val unitName: String?,
    val reserveUrl: String?,
    val phone: String?,
    val infoUrl: String?,
    val addressJson: String?,
    val providerRefJson: String? = null,
    val geomJson: String,
    val propertiesJson: String,
)

internal data class PoiSearchHit(
    val id: Long,
    val name: String,
    val category: String,
    val region: String?,
    val lng: Double,
    val lat: Double,
)

// Outcome of a sampled bbox fetch. `truncated` is true whenever the raw
// count exceeded the global cap, so the FE can show "zoom in for more".
internal data class PoiResult(
    val rows: List<PoiRow>,
    val truncated: Boolean,
)

// Spatial sampling grid. 10x10 = 100 cells. row_number() PARTITION BY cell
// + ORDER BY rn round-robins across cells so pins are spread across viewport.
private const val SAMPLE_GRID_DIM: Int = 10

// Even when one category dominates the viewport, every present category gets
// at least this many slots so sparse layers do not disappear.
private const val MIN_PER_CATEGORY_ALLOCATION: Int = 50

internal class PoiServingRepo(
    private val ctx: DSLContext,
) {
    fun fetchPois(
        bbox: Bbox,
        categories: List<String>?,
        defaultCategories: List<String>,
        limit: Int,
    ): PoiResult {
        val cats = categories ?: defaultCategories
        if (cats.isEmpty()) return PoiResult(emptyList(), truncated = false)

        val countByCat = countByCategory(cats, bbox)
        val rawTotal = countByCat.values.sum()
        val present = cats.filter { (countByCat[it] ?: 0) > 0 }
        if (present.isEmpty()) return PoiResult(emptyList(), truncated = false)

        val allocation = allocateBudget(present.associateWith { countByCat[it] ?: 0 }, limit)
        val rows = fetchSampled(bbox = bbox, allocation = allocation)
        val truncated = rows.size < rawTotal
        return PoiResult(rows, truncated)
    }

    fun fetchPoiById(poiId: Long): PoiDetailRow? {
        val r =
            ctx.fetchOne(
                """
                SELECT id, source, source_id, category, subcategory, name,
                       region, unit_name, reserve_url, phone, info_url,
                       address::text AS address_text,
                       provider_ref::text AS provider_ref_text,
                       ST_AsGeoJSON(geom) AS geom_json,
                       properties::text AS properties_text
                FROM pois
                WHERE id = ?
                  AND deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ) ?: return null
        return PoiDetailRow(
            id = (r.get("id") as Number).toLong(),
            source = r.get("source") as String,
            sourceId = r.get("source_id") as String,
            category = r.get("category") as String,
            subcategory = r.get("subcategory") as String?,
            name = r.get("name") as String,
            region = r.get("region") as String?,
            unitName = r.get("unit_name") as String?,
            reserveUrl = r.get("reserve_url") as String?,
            phone = r.get("phone") as String?,
            infoUrl = r.get("info_url") as String?,
            addressJson = r.get("address_text") as String?,
            providerRefJson = r.get("provider_ref_text") as String?,
            geomJson = r.get("geom_json") as String,
            propertiesJson = r.get("properties_text") as String,
        )
    }

    fun search(
        query: String,
        categories: List<String>,
        limit: Int,
    ): List<PoiSearchHit> {
        val terms = splitPoiSearchTerms(query)
        if (terms.isEmpty()) return emptyList()

        val termPredicate = terms.joinToString("\n                      AND ") { "name ILIKE ? ESCAPE '\\'" }
        val patterns = terms.map { "%${escapeLikePattern(it)}%" }
        val prefix = "${escapeLikePattern(terms.first())}%"
        val distinctCategories = categories.distinct()
        val categoryPredicate =
            distinctCategories
                .takeIf { it.isNotEmpty() }
                ?.joinToString(prefix = "AND category IN (", postfix = ")") { "?" }
                .orEmpty()
        val args =
            buildList<Any> {
                addAll(patterns)
                addAll(distinctCategories)
                add(prefix)
                add(limit)
            }

        return ctx
            .fetch(
                """
                SELECT id, name, category, region,
                       ST_X(geom) AS lng, ST_Y(geom) AS lat
                FROM pois
                WHERE deleted_at IS NULL
                  AND $termPredicate
                  $categoryPredicate
                ORDER BY (name ILIKE ? ESCAPE '\') DESC, length(name) ASC, name ASC
                LIMIT ?
                """.trimIndent(),
                *args.toTypedArray(),
            ).map { r ->
                PoiSearchHit(
                    id = (r.get("id") as Number).toLong(),
                    name = r.get("name") as String,
                    category = r.get("category") as String,
                    region = r.get("region") as String?,
                    lng = (r.get("lng") as Number).toDouble(),
                    lat = (r.get("lat") as Number).toDouble(),
                )
            }
    }

    private fun countByCategory(
        cats: List<String>,
        bbox: Bbox,
    ): Map<String, Int> {
        if (cats.isEmpty()) return emptyMap()
        val placeholders = cats.joinToString(",") { "?" }
        val sql =
            """
            SELECT category, COUNT(*) AS n
            FROM pois
            WHERE deleted_at IS NULL
              AND category IN ($placeholders)
              AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
            GROUP BY category
            """.trimIndent()
        val args = mutableListOf<Any>()
        args.addAll(cats)
        args.add(bbox.west)
        args.add(bbox.south)
        args.add(bbox.east)
        args.add(bbox.north)
        val out = mutableMapOf<String, Int>()
        for (r in ctx.fetch(sql, *args.toTypedArray())) {
            out[r.get("category") as String] = (r.get("n") as Number).toInt()
        }
        return out
    }

    /**
     * Distribute a global cap across categories with viewport presence:
     *
     *   - Sparse layers get `MIN_PER_CATEGORY_ALLOCATION` (or full count if less).
     *   - Remaining budget splits proportional to remaining category count.
     */
    private fun allocateBudget(
        presentCounts: Map<String, Int>,
        cap: Int,
    ): Map<String, Int> {
        val baseline = presentCounts.mapValues { (_, n) -> minOf(n, MIN_PER_CATEGORY_ALLOCATION) }
        val baselineSum = baseline.values.sum()
        if (baselineSum >= cap) return baseline
        val remaining = cap - baselineSum
        val excess = presentCounts.mapValues { (k, n) -> (n - baseline.getValue(k)).coerceAtLeast(0) }
        val excessTotal = excess.values.sum()
        if (excessTotal == 0) return baseline
        val extra =
            excess.mapValues { (_, e) ->
                ((e.toLong() * remaining) / excessTotal).toInt()
            }
        return presentCounts.mapValues { (k, _) -> baseline.getValue(k) + (extra[k] ?: 0) }
    }

    private fun fetchSampled(
        bbox: Bbox,
        allocation: Map<String, Int>,
    ): List<PoiRow> {
        val cats = allocation.keys.toList()
        if (cats.isEmpty()) return emptyList()

        val dx = (bbox.east - bbox.west) / SAMPLE_GRID_DIM
        val dy = (bbox.north - bbox.south) / SAMPLE_GRID_DIM
        val sql =
            buildString {
                cats.forEachIndexed { idx, _ ->
                    if (idx > 0) append("\nUNION ALL\n")
                    append("(SELECT id, category, subcategory, lng, lat FROM (")
                    append(
                        """
                        SELECT id, category, subcategory,
                               ST_X(ST_Centroid(geom)) AS lng,
                               ST_Y(ST_Centroid(geom)) AS lat,
                               row_number() OVER (
                                 PARTITION BY
                                   floor((ST_X(ST_Centroid(geom)) - ?) / ?)::int,
                                   floor((ST_Y(ST_Centroid(geom)) - ?) / ?)::int
                                 ORDER BY id
                               ) AS rn
                        FROM pois
                        WHERE deleted_at IS NULL
                          AND category = ?
                          AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                        """.trimIndent(),
                    )
                    append("\n) sub ORDER BY rn ASC, id ASC LIMIT ?)")
                }
            }

        val args = mutableListOf<Any>()
        for (cat in cats) {
            args.add(bbox.west)
            args.add(dx)
            args.add(bbox.south)
            args.add(dy)
            args.add(cat)
            args.add(bbox.west)
            args.add(bbox.south)
            args.add(bbox.east)
            args.add(bbox.north)
            args.add(allocation.getValue(cat).coerceAtLeast(1))
        }

        return ctx.fetch(sql, *args.toTypedArray()).map { r ->
            PoiRow(
                id = (r.get("id") as Number).toLong(),
                category = r.get("category") as String,
                subcategory = r.get("subcategory") as String?,
                lng = (r.get("lng") as Number).toDouble(),
                lat = (r.get("lat") as Number).toDouble(),
            )
        }
    }
}

private fun splitPoiSearchTerms(q: String): List<String> =
    q
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun escapeLikePattern(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
