package ca.floo.roadtrip.repo

import org.jooq.DSLContext

data class Bbox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

// Slim row shape for the bbox endpoint. Just enough for MapLibre to place
// + color/filter a pin: id, lat/lng, category for color band, subcategory
// for the campground sub-bucket, agency for layer filtering. Everything
// richer lives behind GET /api/pois/{id}.
internal data class PoiRow(
    val id: Long,
    val category: String,
    val subcategory: String?,
    val agency: String?,
    val lng: Double,
    val lat: Double,
)

// Wide row shape returned by GET /api/pois/{id}. Same projection the bbox
// endpoint used to ship for every row; now paid for only on pin click.
internal data class PoiDetailRow(
    val id: Long,
    val source: String,
    val providerSource: String? = null,
    val sourceId: String,
    val category: String,
    val subcategory: String?,
    val agency: String? = null,
    val name: String,
    val region: String?,
    val country: String? = null,
    val lng: Double? = null,
    val lat: Double? = null,
    val unitName: String?,
    val reserveUrl: String?,
    val phone: String?,
    val infoUrl: String?,
    val addressJson: String?,
    val providerRefJson: String? = null,
    val geomJson: String,
    val propertiesJson: String,
    val ctaProviderRefJson: String? = null,
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
                SELECT p.id,
                       COALESCE(primary_gvr.vendor, p.poi_type) AS source,
                       provider_gvr.vendor AS provider_source,
                       COALESCE(primary_gvr.external_id, ts.location_slug, pf.location_id, p.id::text) AS source_id,
                       p.poi_type AS category,
                       cg.kind AS subcategory,
                       cg.management->>'agency' AS agency,
                       COALESCE(cg.name, ts.common_site_name, pf.name) AS name,
                       COALESCE(cg.location->>'region', ts.region, pf.region) AS region,
                       COALESCE(cg.location->>'country', ts.country, pf.country) AS country,
                       ST_X(ST_PointOnSurface(p.geom)) AS lng,
                       ST_Y(ST_PointOnSurface(p.geom)) AS lat,
                       NULL::text AS unit_name,
                       cg.reservation_url AS reserve_url,
                       COALESCE(cg.contact->>'phone', pf.phone) AS phone,
                       COALESCE(ts.info_url, pf.info_url, cg.links->0->>'url') AS info_url,
                       COALESCE(cg.location, ts.address, pf.address, '{}'::jsonb)::text AS address_text,
                       provider_gvr.payload::text AS provider_ref_text,
                       NULL::text AS cta_provider_ref_text,
                       ST_AsGeoJSON(p.geom) AS geom_json,
                       COALESCE(to_jsonb(cg), to_jsonb(ts), to_jsonb(pf), '{}'::jsonb)::text AS properties_text
                FROM pois p
                LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
                LEFT JOIN campgrounds cg ON cg.id = pc.campground_id
                LEFT JOIN LATERAL (
                  SELECT vr.vendor, vr.external_id, vr.payload
                  FROM campground_vendor_refs cvr
                  JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                  WHERE cvr.campground_id = cg.id
                    AND vr.entity_type = 'campground'
                    AND vr.deleted_at IS NULL
                  ORDER BY cvr.is_primary DESC, cvr.vendor_ref_id ASC
                  LIMIT 1
                ) primary_gvr ON true
                LEFT JOIN LATERAL (
                  SELECT vr.vendor, vr.payload
                  FROM campground_vendor_refs cvr
                  JOIN vendor_refs vr ON vr.id = cvr.vendor_ref_id
                  WHERE cvr.campground_id = cg.id
                    AND vr.entity_type = 'campground'
                    AND vr.deleted_at IS NULL
                  ORDER BY
                    CASE WHEN ${providerRefShapeSql("vr.payload")} THEN 1 ELSE 0 END DESC,
                    cvr.is_primary DESC,
                    cvr.vendor_ref_id ASC
                  LIMIT 1
                ) provider_gvr ON true
                LEFT JOIN poi_tesla_superchargers pts ON pts.poi_id = p.id
                LEFT JOIN tesla_superchargers ts ON ts.id = pts.tesla_supercharger_id
                LEFT JOIN poi_planet_fitness_locations ppf ON ppf.poi_id = p.id
                LEFT JOIN planet_fitness_locations pf ON pf.id = ppf.planet_fitness_location_id
                WHERE p.id = ?
                  AND p.deleted_at IS NULL
                """.trimIndent(),
                poiId,
            ) ?: return null
        return PoiDetailRow(
            id = (r.get("id") as Number).toLong(),
            source = r.get("source") as String,
            providerSource = r.get("provider_source") as String?,
            sourceId = r.get("source_id") as String,
            category = r.get("category") as String,
            subcategory = r.get("subcategory") as String?,
            agency = r.get("agency") as String?,
            name = r.get("name") as String,
            region = r.get("region") as String?,
            country = r.get("country") as String?,
            lng = (r.get("lng") as Number?)?.toDouble(),
            lat = (r.get("lat") as Number?)?.toDouble(),
            unitName = r.get("unit_name") as String?,
            reserveUrl = r.get("reserve_url") as String?,
            phone = r.get("phone") as String?,
            infoUrl = r.get("info_url") as String?,
            addressJson = r.get("address_text") as String?,
            providerRefJson = r.get("provider_ref_text") as String?,
            geomJson = r.get("geom_json") as String,
            propertiesJson = r.get("properties_text") as String,
            ctaProviderRefJson = r.get("cta_provider_ref_text") as String?,
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
                FROM (
                    SELECT p.id,
                           p.geom,
                           p.poi_type AS category,
                           COALESCE(cg.name, ts.common_site_name, pf.name) AS name,
                           COALESCE(cg.location->>'region', ts.region, pf.region) AS region
                    FROM pois p
                    LEFT JOIN poi_campgrounds pc ON pc.poi_id = p.id
                    LEFT JOIN campgrounds cg ON cg.id = pc.campground_id
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
                        LEFT JOIN campgrounds cg ON cg.id = pc.campground_id
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

private fun providerRefShapeSql(payloadExpression: String): String =
    """
    (
      jsonb_exists($payloadExpression, 'recgov_id')
      OR (jsonb_exists($payloadExpression, 'mapId') AND jsonb_exists($payloadExpression, 'transactionLocationId'))
      OR jsonb_exists($payloadExpression, 'park_id')
      OR jsonb_exists($payloadExpression, 'facility_id')
      OR jsonb_exists($payloadExpression, 'place_id')
    )
    """.trimIndent()
