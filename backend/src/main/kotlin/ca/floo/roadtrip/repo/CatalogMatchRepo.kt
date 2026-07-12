package ca.floo.roadtrip.repo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * Persistence for cross-vendor catalog identity.
 *
 * Owns writes into `campground_matches` / `campsite_matches` and maintenance of
 * the `match_group_id` column via iterative label propagation. Pair rows are
 * always normalized with `aId < bId` (enforced both here and by the CHECK
 * constraint on the underlying tables).
 */
open class CatalogMatchRepo(
    private val ctx: DSLContext,
) {
    /** Normalized match pair with heuristic metadata to persist as jsonb. */
    data class MatchPair(
        val aId: Long,
        val bId: Long,
        val heuristic: JsonObject,
    ) {
        init {
            require(aId < bId) { "MatchPair must be normalized with aId < bId (got aId=$aId, bId=$bId)" }
        }
    }

    /**
     * Candidate campground pair for the geo+name pass. The repo enforces
     * cross-etl-source, within-distance, and not-already-matched; the service
     * decides whether the names are close enough.
     */
    data class GeoNameCandidate(
        val aId: Long,
        val bId: Long,
        val aName: String,
        val bName: String,
        val distanceM: Double,
    )

    /**
     * Candidate campsite pair whose parent campgrounds share a match group.
     * The service does exact normalized (loop, name) equality.
     */
    data class CampsiteNameCandidate(
        val aId: Long,
        val bId: Long,
        val aLoop: String?,
        val bLoop: String?,
        val aName: String,
        val bName: String,
    )

    open fun upsertCampgroundMatches(pairs: List<MatchPair>): Int =
        upsertMatches(
            pairs = pairs,
            table = "campground_matches",
            aColumn = "campground_a_id",
            bColumn = "campground_b_id",
        )

    open fun upsertCampsiteMatches(pairs: List<MatchPair>): Int =
        upsertMatches(
            pairs = pairs,
            table = "campsite_matches",
            aColumn = "campsite_a_id",
            bColumn = "campsite_b_id",
        )

    private fun upsertMatches(
        pairs: List<MatchPair>,
        table: String,
        aColumn: String,
        bColumn: String,
    ): Int {
        if (pairs.isEmpty()) return 0
        return ctx.transactionResult { cfg ->
            val txn = DSL.using(cfg)
            pairs.sumOf { pair ->
                txn.execute(
                    """
                    INSERT INTO $table ($aColumn, $bColumn, heuristic, created_at, updated_at)
                    VALUES (?, ?, ?::jsonb, now(), now())
                    ON CONFLICT ($aColumn, $bColumn)
                    DO UPDATE SET
                      heuristic = EXCLUDED.heuristic,
                      updated_at = now()
                    """.trimIndent(),
                    pair.aId,
                    pair.bId,
                    pair.heuristic.toString(),
                )
            }
        }
    }

    open fun sharedVendorRefCampgroundPairs(): List<MatchPair> =
        sharedVendorRefPairs(
            entityType = CAMPGROUND_ENTITY,
            linkTable = "campground_vendor_refs",
            linkColumn = "campground_id",
        )

    open fun sharedVendorRefCampsitePairs(): List<MatchPair> =
        sharedVendorRefPairs(
            entityType = CAMPSITE_ENTITY,
            linkTable = "campsite_vendor_refs",
            linkColumn = "campsite_id",
        )

    private fun sharedVendorRefPairs(
        entityType: String,
        linkTable: String,
        linkColumn: String,
    ): List<MatchPair> {
        // Two catalog rows share a "vendor_ref" when they resolve to the same
        // (vendor, entity_type, external_id) triple. The unique index on
        // vendor_refs collapses the triple to a single active row, so the join
        // usually reduces to one vendor_ref linked to two catalog rows via
        // *_vendor_refs (case a). Joining across vendor_refs on the triple also
        // handles the corner case of a soft-deleted duplicate (case b) without
        // materially changing the plan for the common case.
        val rows =
            ctx.fetch(
                """
                SELECT DISTINCT
                  LEAST(l1.$linkColumn, l2.$linkColumn)    AS a_id,
                  GREATEST(l1.$linkColumn, l2.$linkColumn) AS b_id,
                  vr1.vendor      AS vendor,
                  vr1.external_id AS external_id
                FROM vendor_refs vr1
                JOIN $linkTable l1 ON l1.vendor_ref_id = vr1.id
                JOIN vendor_refs vr2
                  ON vr2.vendor = vr1.vendor
                 AND vr2.entity_type = vr1.entity_type
                 AND vr2.external_id = vr1.external_id
                 AND vr2.deleted_at IS NULL
                JOIN $linkTable l2 ON l2.vendor_ref_id = vr2.id
                WHERE vr1.entity_type = ?
                  AND vr1.deleted_at IS NULL
                  AND l1.$linkColumn < l2.$linkColumn
                """.trimIndent(),
                entityType,
            )
        return rows.map { record ->
            val vendor = record.get("vendor", String::class.java)
            val externalId = record.get("external_id", String::class.java)
            MatchPair(
                aId = record.get("a_id", Long::class.java),
                bId = record.get("b_id", Long::class.java),
                heuristic = sharedVendorRefHeuristic(vendor, externalId),
            )
        }
    }

    open fun geoNameCampgroundCandidates(maxDistanceM: Double): List<GeoNameCandidate> {
        val rows =
            ctx.fetch(
                """
                SELECT
                  a.id   AS a_id,
                  b.id   AS b_id,
                  a.name AS a_name,
                  b.name AS b_name,
                  ST_Distance(
                    ST_SetSRID(ST_MakePoint(
                      (a.location->>'longitude')::double precision,
                      (a.location->>'latitude')::double precision
                    ), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(
                      (b.location->>'longitude')::double precision,
                      (b.location->>'latitude')::double precision
                    ), 4326)::geography
                  ) AS distance_m
                FROM campgrounds a
                JOIN campgrounds b
                  ON a.id < b.id
                 AND a.data_source <> b.data_source
                WHERE a.deleted_at IS NULL
                  AND b.deleted_at IS NULL
                  AND (a.location->>'latitude')  IS NOT NULL
                  AND (a.location->>'longitude') IS NOT NULL
                  AND (b.location->>'latitude')  IS NOT NULL
                  AND (b.location->>'longitude') IS NOT NULL
                  AND ST_DWithin(
                        ST_SetSRID(ST_MakePoint(
                          (a.location->>'longitude')::double precision,
                          (a.location->>'latitude')::double precision
                        ), 4326)::geography,
                        ST_SetSRID(ST_MakePoint(
                          (b.location->>'longitude')::double precision,
                          (b.location->>'latitude')::double precision
                        ), 4326)::geography,
                        ?
                      )
                  AND NOT EXISTS (
                        SELECT 1 FROM campground_matches m
                        WHERE m.campground_a_id = a.id AND m.campground_b_id = b.id
                      )
                """.trimIndent(),
                maxDistanceM,
            )
        return rows.map { record ->
            GeoNameCandidate(
                aId = record.get("a_id", Long::class.java),
                bId = record.get("b_id", Long::class.java),
                aName = record.get("a_name", String::class.java),
                bName = record.get("b_name", String::class.java),
                distanceM = record.get("distance_m", Double::class.java),
            )
        }
    }

    open fun campsiteNameCandidates(): List<CampsiteNameCandidate> {
        val rows =
            ctx.fetch(
                """
                WITH matched_cg_pairs AS (
                  SELECT a.id AS cg_a_id, b.id AS cg_b_id
                  FROM campgrounds a
                  JOIN campgrounds b
                    ON a.match_group_id = b.match_group_id
                   AND a.data_source <> b.data_source
                   AND a.id < b.id
                  WHERE a.deleted_at IS NULL
                    AND b.deleted_at IS NULL
                    AND a.match_group_id IS NOT NULL
                )
                SELECT
                  sa.id        AS a_id,
                  sb.id        AS b_id,
                  sa.loop_name AS a_loop,
                  sb.loop_name AS b_loop,
                  sa.name      AS a_name,
                  sb.name      AS b_name
                FROM matched_cg_pairs p
                JOIN campsites sa ON sa.campground_id = p.cg_a_id AND sa.deleted_at IS NULL
                JOIN campsites sb ON sb.campground_id = p.cg_b_id AND sb.deleted_at IS NULL
                WHERE NULLIF(lower(trim(sa.name)), '') = NULLIF(lower(trim(sb.name)), '')
                  AND NULLIF(lower(trim(sa.loop_name)), '') IS NOT DISTINCT FROM NULLIF(lower(trim(sb.loop_name)), '')
                  AND NOT EXISTS (
                      SELECT 1 FROM campsite_matches m
                      WHERE m.campsite_a_id = LEAST(sa.id, sb.id)
                        AND m.campsite_b_id = GREATEST(sa.id, sb.id)
                    )
                """.trimIndent(),
            )
        return rows.map { record ->
            CampsiteNameCandidate(
                aId = record.get("a_id", Long::class.java),
                bId = record.get("b_id", Long::class.java),
                aLoop = record.get("a_loop", String::class.java),
                bLoop = record.get("b_loop", String::class.java),
                aName = record.get("a_name", String::class.java),
                bName = record.get("b_name", String::class.java),
            )
        }
    }

    /**
     * Iterative label propagation over the match graph: each matched catalog
     * row's `match_group_id` converges on the MIN id of its connected
     * component. Unmatched singleton rows keep `match_group_id` null; canonical
     * views already group them with `COALESCE(match_group_id, id)`.
     * Returns the total number of rows updated across all iterations (both
     * tables); this is what the caller surfaces as "groupsRecomputed".
     *
     * Each seed and propagation chunk commits independently to limit WAL +
     * dirty-buffer pressure on memory-constrained hosts. The algorithm is
     * idempotent and monotone, so partial progress is safe.
     */
    open fun recomputeMatchGroups(): Int {
        var totalUpdated = 0
        totalUpdated +=
            propagate(
                catalogTable = "campgrounds",
                matchesTable = "campground_matches",
                aColumn = "campground_a_id",
                bColumn = "campground_b_id",
            )
        totalUpdated +=
            propagate(
                catalogTable = "campsites",
                matchesTable = "campsite_matches",
                aColumn = "campsite_a_id",
                bColumn = "campsite_b_id",
            )
        return totalUpdated
    }

    private fun propagate(
        catalogTable: String,
        matchesTable: String,
        aColumn: String,
        bColumn: String,
    ): Int {
        var totalUpdated = 0
        // Seed only newly matched endpoints. Reseeding stable groups back to
        // singleton ids rewrites the whole catalog on every matcher run.
        while (true) {
            val seeded =
                ctx.execute(
                    """
                    WITH endpoints AS (
                      SELECT $aColumn AS id FROM $matchesTable
                      UNION
                      SELECT $bColumn AS id FROM $matchesTable
                    ),
                    to_seed AS (
                      SELECT c.id
                        FROM $catalogTable c
                        JOIN endpoints e ON e.id = c.id
                       WHERE c.match_group_id IS NULL
                       LIMIT $PROPAGATE_CHUNK_SIZE
                    )
                    UPDATE $catalogTable AS target
                       SET match_group_id = target.id
                      FROM to_seed
                     WHERE target.id = to_seed.id
                    """.trimIndent(),
                )
            totalUpdated += seeded
            if (seeded < PROPAGATE_CHUNK_SIZE) break
        }
        var iteration = 0
        while (true) {
            iteration += 1
            if (iteration > MAX_LABEL_PROPAGATION_ITERATIONS) {
                throw IllegalStateException(
                    "label propagation did not converge in $MAX_LABEL_PROPAGATION_ITERATIONS iterations for $catalogTable",
                )
            }
            var iterationUpdated = 0
            while (true) {
                val updated =
                    ctx.execute(
                        """
                        WITH pairs AS (
                          SELECT $aColumn AS a, $bColumn AS b FROM $matchesTable
                          UNION ALL
                          SELECT $bColumn AS a, $aColumn AS b FROM $matchesTable
                        ),
                        new_groups AS (
                          SELECT node.id,
                                 node.match_group_id AS old_group,
                                 LEAST(node.match_group_id, MIN(other.match_group_id)) AS new_group
                            FROM $catalogTable node
                            JOIN pairs p ON p.a = node.id
                            JOIN $catalogTable other ON other.id = p.b
                           GROUP BY node.id, node.match_group_id
                        ),
                        to_update AS (
                          SELECT id, new_group
                            FROM new_groups
                           WHERE old_group <> new_group
                           ORDER BY id
                           LIMIT $PROPAGATE_CHUNK_SIZE
                        )
                        UPDATE $catalogTable AS target
                           SET match_group_id = to_update.new_group
                          FROM to_update
                         WHERE target.id = to_update.id
                        """.trimIndent(),
                    )
                totalUpdated += updated
                iterationUpdated += updated
                if (updated < PROPAGATE_CHUNK_SIZE) break
            }
            if (iterationUpdated == 0) break
        }
        return totalUpdated
    }

    companion object {
        const val MAX_LABEL_PROPAGATION_ITERATIONS = 32
        private const val PROPAGATE_CHUNK_SIZE = 5000
        private const val CAMPGROUND_ENTITY = "campground"
        private const val CAMPSITE_ENTITY = "campsite"

        fun sharedVendorRefHeuristic(
            vendor: String,
            externalId: String,
        ): JsonObject =
            buildJsonObject {
                put("method", JsonPrimitive("shared_vendor_ref"))
                put("score", JsonPrimitive(1.0))
                put("vendor", JsonPrimitive(vendor))
                put("external_id", JsonPrimitive(externalId))
            }
    }
}
