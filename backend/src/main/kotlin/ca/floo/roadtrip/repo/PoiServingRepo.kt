package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.domain.poi.Bbox
import ca.floo.roadtrip.models.domain.poi.PoiIndexRow
import ca.floo.roadtrip.models.domain.poi.PoiResult
import ca.floo.roadtrip.models.domain.poi.PoiRow
import ca.floo.roadtrip.models.domain.poi.PoiSearchHit
import org.jooq.DSLContext

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

    fun fetchPoisWithinPolygon(
        polygonGeoJson: String,
        categories: List<String>,
    ): List<PoiRow> {
        if (categories.isEmpty()) return emptyList()
        val placeholders = categories.joinToString(",") { "?" }
        val sql =
            """
            WITH corridor AS (
              SELECT ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) AS poly
            ),
            candidates AS (
              SELECT
                id,
                category,
                subcategory,
                agency,
                ST_X(ST_Centroid(geom)) AS lng,
                ST_Y(ST_Centroid(geom)) AS lat,
                CASE
                  WHEN category = 'campground' AND NULLIF(provider_ref ->> 'recgov_id', '') IS NOT NULL
                    THEN 'recgov:' || (provider_ref ->> 'recgov_id')
                  WHEN category = 'campground'
                    AND NULLIF(provider_ref ->> 'transactionLocationId', '') IS NOT NULL
                    AND NULLIF(provider_ref ->> 'mapId', '') IS NOT NULL
                    THEN 'aspira:' || source || ':' ||
                      (provider_ref ->> 'transactionLocationId') || ':' ||
                      (provider_ref ->> 'mapId')
                  WHEN category = 'campground' AND NULLIF(provider_ref ->> 'park_id', '') IS NOT NULL
                    THEN 'reserveamerica:' || source || ':' || (provider_ref ->> 'park_id')
                  WHEN category = 'campground' AND NULLIF(provider_ref ->> 'facility_id', '') IS NOT NULL
                    THEN 'reserveamerica:' || source || ':' || (provider_ref ->> 'facility_id')
                  ELSE source || ':' || source_id
                END AS poi_key
              FROM (
                SELECT
                  p.id,
                  p.geom,
                  p.poi_type AS category,
                  cg.kind AS subcategory,
                  cg.management->>'agency' AS agency,
                  COALESCE(gvr.vendor, p.poi_type) AS source,
                  COALESCE(gvr.external_id, ts.location_slug, pf.location_id, p.id::text) AS source_id,
                  COALESCE(gvr.payload, '{}'::jsonb) AS provider_ref
                FROM pois p
                LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
                LEFT JOIN campground_canonical cg ON cg.id = pc.campground_id
                LEFT JOIN LATERAL (
                  SELECT vr.vendor, vr.external_id, vr.payload
                  FROM campground_vendor_refs cvr
                  JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                  WHERE cvr.campground_id = cg.id
                    AND vr.entity_type = 'campground'
                    AND vr.deleted_at IS NULL
                  ORDER BY cvr.vendor_ref_id ASC
                  LIMIT 1
                ) gvr ON TRUE
                LEFT JOIN poi_tesla_superchargers pts ON pts.poi_id = p.id
                LEFT JOIN tesla_superchargers ts ON ts.id = pts.tesla_supercharger_id AND ts.deleted_at IS NULL
                LEFT JOIN poi_planet_fitness_locations ppf ON ppf.poi_id = p.id
                LEFT JOIN planet_fitness_locations pf ON pf.id = ppf.planet_fitness_location_id AND pf.deleted_at IS NULL
                WHERE p.deleted_at IS NULL
              ) catalog, corridor
              WHERE category IN ($placeholders)
                AND ST_Within(ST_Centroid(geom), corridor.poly)
            ),
            ranked AS (
              SELECT *,
                     ROW_NUMBER() OVER (PARTITION BY poi_key ORDER BY id ASC) AS rn
              FROM candidates
            )
            SELECT id, category, subcategory, agency, lng, lat
            FROM ranked
            WHERE rn = 1
            ORDER BY id ASC
            """.trimIndent()
        val args = mutableListOf<Any>()
        args.add(polygonGeoJson)
        args.addAll(categories)
        return ctx.fetch(sql, *args.toTypedArray()).map { r ->
            PoiRow(
                id = (r.get("id") as Number).toLong(),
                category = r.get("category") as String,
                subcategory = r.get("subcategory") as String?,
                agency = r.get("agency") as String?,
                lng = (r.get("lng") as Number).toDouble(),
                lat = (r.get("lat") as Number).toDouble(),
            )
        }
    }

    fun findById(poiId: Long): PoiIndexRow? =
        ctx
            .fetchOne(
                """
                SELECT id,
                       poi_type AS category,
                       ST_X(ST_PointOnSurface(geom)) AS lng,
                       ST_Y(ST_PointOnSurface(geom)) AS lat,
                       ST_AsGeoJSON(geom) AS geom_json
                FROM pois
                WHERE id = ?
                  AND deleted_at IS NULL
                """.trimIndent(),
                poiId,
            )?.let { row ->
                PoiIndexRow(
                    id = row.get("id", Long::class.java),
                    category = row.get("category", String::class.java),
                    lng = row.get("lng", Double::class.java),
                    lat = row.get("lat", Double::class.java),
                    geomJson = row.get("geom_json", String::class.java),
                )
            }

    fun fetchPoiName(poiId: Long): String? =
        ctx
            .fetchOne(
                """
                SELECT COALESCE(cg.name, ts.common_site_name, pf.name) AS name
                FROM pois p
                LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
                LEFT JOIN campground_canonical cg ON cg.id = pc.campground_id
                LEFT JOIN poi_tesla_superchargers pts ON pts.poi_id = p.id
                LEFT JOIN tesla_superchargers ts ON ts.id = pts.tesla_supercharger_id
                LEFT JOIN poi_planet_fitness_locations ppf ON ppf.poi_id = p.id
                LEFT JOIN planet_fitness_locations pf ON pf.id = ppf.planet_fitness_location_id
                WHERE p.id = ?
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            )?.get("name", String::class.java)

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
                FROM (
                    SELECT p.id,
                           p.geom,
                           p.poi_type AS category,
                           COALESCE(cg.name, ts.common_site_name, pf.name) AS name,
                           COALESCE(cg.location->>'region', ts.region, pf.region) AS region
                    FROM pois p
                    LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
                    LEFT JOIN campground_canonical cg ON cg.id = pc.campground_id
                    LEFT JOIN poi_tesla_superchargers pts ON pts.poi_id = p.id
                    LEFT JOIN tesla_superchargers ts ON ts.id = pts.tesla_supercharger_id
                    LEFT JOIN poi_planet_fitness_locations ppf ON ppf.poi_id = p.id
                    LEFT JOIN planet_fitness_locations pf ON pf.id = ppf.planet_fitness_location_id
                    WHERE p.deleted_at IS NULL
                ) catalog
                WHERE $termPredicate
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
            SELECT poi_type AS category, COUNT(*) AS n
            FROM pois
            WHERE deleted_at IS NULL
              AND poi_type IN ($placeholders)
              AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
            GROUP BY poi_type
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
                    append("(SELECT id, category, subcategory, agency, lng, lat FROM (")
                    append(
                        """
                        SELECT p.id,
                               p.poi_type AS category,
                               cg.kind AS subcategory,
                               cg.management->>'agency' AS agency,
                               ST_X(ST_Centroid(p.geom)) AS lng,
                               ST_Y(ST_Centroid(p.geom)) AS lat,
                               row_number() OVER (
                                 PARTITION BY
                                   floor((ST_X(ST_Centroid(p.geom)) - ?) / ?)::int,
                                   floor((ST_Y(ST_Centroid(p.geom)) - ?) / ?)::int
                                 ORDER BY p.id
                               ) AS rn
                        FROM pois p
                        LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
                        LEFT JOIN campground_canonical cg ON cg.id = pc.campground_id
                        WHERE p.deleted_at IS NULL
                          AND p.poi_type = ?
                          AND p.geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
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
                agency = r.get("agency") as String?,
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
