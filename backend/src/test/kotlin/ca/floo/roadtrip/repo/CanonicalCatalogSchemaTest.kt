package ca.floo.roadtrip.repo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalCatalogSchemaTest : SharedDbTest() {
    @Test
    fun `canonical catalog tables exist`() {
        val tables =
            ctx
                .fetch(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN (
                        'pois',
                        'campgrounds',
                        'campsites',
                        'vendor_refs',
                        'campground_vendor_refs',
                        'campsite_vendor_refs',
                        'poi_campgrounds',
                        'tesla_superchargers',
                        'poi_tesla_superchargers',
                        'planet_fitness_locations',
                        'poi_planet_fitness_locations'
                      )
                    ORDER BY table_name
                    """.trimIndent(),
                ).map { it.get("table_name", String::class.java) }

        assertEquals(
            listOf(
                "campground_vendor_refs",
                "campgrounds",
                "campsite_vendor_refs",
                "campsites",
                "planet_fitness_locations",
                "poi_campgrounds",
                "poi_planet_fitness_locations",
                "poi_tesla_superchargers",
                "pois",
                "tesla_superchargers",
                "vendor_refs",
            ),
            tables,
        )
    }

    @Test
    fun `campgrounds table has Campflare top level fields`() {
        val columns = columnNames("campgrounds")

        assertEquals(
            listOf(
                "alerts",
                "amenities",
                "big_rig_friendly",
                "cell_service",
                "connections",
                "contact",
                "created_at",
                "data_source",
                "default_campsite_schedule",
                "deleted_at",
                "has_pull_through_sites",
                "id",
                "kind",
                "links",
                "location",
                "long_description",
                "management",
                "match_group_id",
                "max_rv_length",
                "max_trailer_length",
                "medium_description",
                "metadata",
                "name",
                "photos",
                "preferred_availability_source",
                "price",
                "primary_vendor_ref_id",
                "reservation_url",
                "short_description",
                "source_payload",
                "status",
                "status_description",
                "updated_at",
            ),
            columns,
        )
    }

    @Test
    fun `campsites table has Campflare top level fields`() {
        val columns = columnNames("campsites")

        assertEquals(
            listOf(
                "ada_accessible",
                "campground_id",
                "created_at",
                "data_source",
                "deleted_at",
                "driveway_length",
                "electric_hookups",
                "equipment",
                "firepit",
                "id",
                "kind",
                "kind_listed",
                "latitude",
                "longitude",
                "loop_name",
                "match_group_id",
                "max_cars",
                "max_people",
                "max_rv_length",
                "max_trailer_length",
                "name",
                "photos",
                "picnic_table",
                "price",
                "primary_vendor_ref_id",
                "pull_through",
                "reservation_url",
                "schedule",
                "sewer_hookups",
                "source_payload",
                "updated_at",
                "water_hookups",
            ),
            columns,
        )
    }

    @Test
    fun `pois table is a lean spatial wrapper`() {
        val columns = columnNames("pois")
        val retiredColumns =
            ctx
                .fetch(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'pois'
                      AND column_name IN (
                        'source',
                        'source_id',
                        'category',
                        'subcategory',
                        'agency',
                        'name',
                        'region',
                        'country',
                        'unit_name',
                        'phone',
                        'address',
                        'info_url',
                        'provider_ref',
                        'properties',
                        'reserve_url'
                      )
                    ORDER BY column_name
                    """.trimIndent(),
                ).map { it.get("column_name", String::class.java) }

        assertEquals(
            listOf(
                "cadence_override_sec",
                "created_at",
                "deleted_at",
                "geom",
                "id",
                "poi_type",
                "updated_at",
            ),
            columns,
        )
        assertEquals(emptyList<String>(), retiredColumns)
    }

    @Test
    fun `tesla superchargers table has typed operational columns`() {
        val columns =
            ctx
                .fetch(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = 'tesla_superchargers'
                      AND column_name IN (
                        'location_slug',
                        'location_guid',
                        'common_site_name',
                        'site_status',
                        'access_type',
                        'open_to_public',
                        'open_to_non_teslas',
                        'trailer_friendly',
                        'twenty_four_seven',
                        'stall_count',
                        'max_power_kw',
                        'address',
                        'region',
                        'country',
                        'time_zone',
                        'amenities',
                        'hardware_counts',
                        'pricebooks',
                        'availability_profile',
                        'info_url',
                        'index_payload',
                        'detail_payload'
                      )
                    ORDER BY column_name
                    """.trimIndent(),
                ).map { it.get("column_name", String::class.java) }

        assertEquals(
            listOf(
                "access_type",
                "address",
                "amenities",
                "availability_profile",
                "common_site_name",
                "country",
                "detail_payload",
                "hardware_counts",
                "index_payload",
                "info_url",
                "location_guid",
                "location_slug",
                "max_power_kw",
                "open_to_non_teslas",
                "open_to_public",
                "pricebooks",
                "region",
                "site_status",
                "stall_count",
                "time_zone",
                "trailer_friendly",
                "twenty_four_seven",
            ),
            columns,
        )
    }

    @Test
    fun `vendor refs are unique per vendor entity and external id`() {
        val constraintCount =
            ctx
                .fetchOne(
                    """
                    SELECT COUNT(*) AS n
                    FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND tablename = 'vendor_refs'
                      AND indexname = 'vendor_refs_vendor_entity_external_uidx'
                    """.trimIndent(),
                )!!
                .get("n", Number::class.java)
                .toInt()

        assertEquals(1, constraintCount)
    }

    @Test
    fun `availability watch targets reference canonical poi and campsite tables`() {
        val refs =
            ctx
                .fetch(
                    """
                    SELECT
                      tc.table_name || '.' || kcu.column_name || '->' || ccu.table_name || '.' || ccu.column_name AS ref
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name
                     AND tc.table_schema = kcu.table_schema
                    JOIN information_schema.constraint_column_usage ccu
                      ON ccu.constraint_name = tc.constraint_name
                     AND ccu.table_schema = tc.table_schema
                    WHERE tc.constraint_type = 'FOREIGN KEY'
                      AND tc.table_schema = 'public'
                      AND tc.table_name = 'availability_watch_target'
                      AND kcu.column_name IN ('poi_id', 'campsite_id')
                    ORDER BY ref
                    """.trimIndent(),
                ).map { it.get("ref", String::class.java) }

        assertEquals(
            listOf(
                "availability_watch_target.campsite_id->campsites.id",
                "availability_watch_target.poi_id->pois.id",
            ),
            refs,
        )
    }

    @Test
    fun `reservable catalog tables and identity columns are retired`() {
        val oldTables =
            ctx
                .fetch(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN ('reservables', 'reservable_pois')
                    ORDER BY table_name
                    """.trimIndent(),
                ).map { it.get("table_name", String::class.java) }

        val oldColumns =
            ctx
                .fetch(
                    """
                    SELECT table_name || '.' || column_name AS old_column
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND (
                        (table_name = 'availability' AND column_name = 'reservable_id')
                        OR (table_name = 'availability_watch_target' AND column_name = 'reservable_id')
                        OR (table_name = 'availability_watch' AND column_name = 'reservable_filters')
                      )
                    ORDER BY old_column
                    """.trimIndent(),
                ).map { it.get("old_column", String::class.java) }

        val newColumns =
            ctx
                .fetch(
                    """
                    SELECT table_name || '.' || column_name AS new_column
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND (
                        (table_name = 'availability' AND column_name = 'campsite_id')
                        OR (table_name = 'availability_watch_target' AND column_name = 'campsite_id')
                        OR (table_name = 'availability_watch' AND column_name = 'campsite_filters')
                      )
                    ORDER BY new_column
                    """.trimIndent(),
                ).map { it.get("new_column", String::class.java) }

        assertEquals(emptyList<String>(), oldTables)
        assertEquals(emptyList<String>(), oldColumns)
        assertEquals(
            listOf(
                "availability.campsite_id",
                "availability_watch.campsite_filters",
                "availability_watch_target.campsite_id",
            ),
            newColumns,
        )
    }

    @Test
    fun `campground and campsite match tables exist with required columns`() {
        assertEquals(
            listOf(
                "campground_a_id",
                "campground_b_id",
                "created_at",
                "heuristic",
                "id",
                "updated_at",
            ),
            columnNames("campground_matches"),
        )
        assertEquals(
            listOf(
                "campsite_a_id",
                "campsite_b_id",
                "created_at",
                "heuristic",
                "id",
                "updated_at",
            ),
            columnNames("campsite_matches"),
        )

        // Required constraints on both match tables: ordered pairs, jsonb-object
        // heuristic, and a unique index on the ordered pair.
        val matchConstraints =
            ctx
                .fetch(
                    """
                    SELECT table_name || '.' || constraint_name AS ref
                    FROM information_schema.table_constraints
                    WHERE table_schema = 'public'
                      AND table_name IN ('campground_matches', 'campsite_matches')
                      AND constraint_name IN (
                        'campground_matches_order_check',
                        'campground_matches_heuristic_check',
                        'campground_matches_pair_uidx',
                        'campsite_matches_order_check',
                        'campsite_matches_heuristic_check',
                        'campsite_matches_pair_uidx'
                      )
                    ORDER BY ref
                    """.trimIndent(),
                ).map { it.get("ref", String::class.java) }

        assertEquals(
            listOf(
                "campground_matches.campground_matches_heuristic_check",
                "campground_matches.campground_matches_order_check",
                "campground_matches.campground_matches_pair_uidx",
                "campsite_matches.campsite_matches_heuristic_check",
                "campsite_matches.campsite_matches_order_check",
                "campsite_matches.campsite_matches_pair_uidx",
            ),
            matchConstraints,
        )
    }

    @Test
    fun `data_source columns are non-null with non-blank check`() {
        val dataSourceRows =
            ctx
                .fetch(
                    """
                    SELECT table_name || ':' || is_nullable AS ref
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND column_name = 'data_source'
                      AND table_name IN ('campgrounds', 'campsites')
                    ORDER BY table_name
                    """.trimIndent(),
                ).map { it.get("ref", String::class.java) }

        assertEquals(
            listOf("campgrounds:NO", "campsites:NO"),
            dataSourceRows,
        )

        val dataSourceChecks =
            ctx
                .fetch(
                    """
                    SELECT table_name || '.' || constraint_name AS ref
                    FROM information_schema.table_constraints
                    WHERE table_schema = 'public'
                      AND constraint_type = 'CHECK'
                      AND constraint_name IN (
                        'campgrounds_data_source_check',
                        'campsites_data_source_check'
                      )
                    ORDER BY ref
                    """.trimIndent(),
                ).map { it.get("ref", String::class.java) }

        assertEquals(
            listOf(
                "campgrounds.campgrounds_data_source_check",
                "campsites.campsites_data_source_check",
            ),
            dataSourceChecks,
        )
    }

    @Test
    fun `is_primary columns on vendor ref link tables are gone`() {
        val remaining =
            ctx
                .fetch(
                    """
                    SELECT table_name || '.' || column_name AS ref
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND column_name = 'is_primary'
                      AND table_name IN ('campground_vendor_refs', 'campsite_vendor_refs')
                    ORDER BY ref
                    """.trimIndent(),
                ).map { it.get("ref", String::class.java) }

        assertEquals(emptyList<String>(), remaining)
    }

    @Test
    fun `canonical materialized views exist with unique index on id`() {
        val matviews =
            ctx
                .fetch(
                    """
                    SELECT matviewname
                    FROM pg_matviews
                    WHERE schemaname = 'public'
                      AND matviewname IN ('campground_canonical', 'campsite_canonical')
                    ORDER BY matviewname
                    """.trimIndent(),
                ).map { it.get("matviewname", String::class.java) }

        assertEquals(
            listOf("campground_canonical", "campsite_canonical"),
            matviews,
        )

        assertEquals(1, uniqueIdIndexCount("campground_canonical"))
        assertEquals(1, uniqueIdIndexCount("campsite_canonical"))
    }

    private fun uniqueIdIndexCount(matview: String): Int =
        ctx
            .fetchOne(
                """
                SELECT COUNT(*) AS n
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = ?
                  AND indexdef ILIKE 'CREATE UNIQUE INDEX%'
                  AND indexdef ILIKE '%(id)'
                """.trimIndent(),
                matview,
            )!!
            .get("n", Number::class.java)
            .toInt()

    private fun columnNames(tableName: String): List<String> =
        ctx
            .fetch(
                """
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                ORDER BY column_name
                """.trimIndent(),
                tableName,
            ).map { it.get("column_name", String::class.java) }
}
