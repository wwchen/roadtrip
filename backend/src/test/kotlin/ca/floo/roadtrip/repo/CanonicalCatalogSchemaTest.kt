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
                "campgrounds",
                "campsites",
                "planet_fitness_locations",
                "poi_campgrounds",
                "poi_planet_fitness_locations",
                "poi_tesla_superchargers",
                "pois",
                "tesla_superchargers",
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
                "booking_provider",
                "booking_provider_ref",
                "cell_service",
                "connections",
                "contact",
                "created_at",
                "data_provider",
                "data_provider_ref",
                "default_campsite_schedule",
                "deleted_at",
                "has_pull_through_sites",
                "id",
                "kind",
                "links",
                "location",
                "long_description",
                "management",
                "max_rv_length",
                "max_trailer_length",
                "medium_description",
                "metadata",
                "name",
                "photos",
                "price",
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
                "booking_provider",
                "booking_provider_ref",
                "campground_id",
                "created_at",
                "data_provider",
                "data_provider_ref",
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
                "max_cars",
                "max_people",
                "max_rv_length",
                "max_trailer_length",
                "name",
                "photos",
                "picnic_table",
                "price",
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
    fun `provider columns have unique index on campgrounds and campsites`() {
        val indexes =
            ctx
                .fetch(
                    """
                    SELECT tablename || '.' || indexname AS ref
                    FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname IN ('campgrounds_provider_uidx', 'campsites_provider_uidx')
                    ORDER BY ref
                    """.trimIndent(),
                ).map { it.get("ref", String::class.java) }

        assertEquals(
            listOf(
                "campgrounds.campgrounds_provider_uidx",
                "campsites.campsites_provider_uidx",
            ),
            indexes,
        )
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
    fun `catalog match tables and columns are removed`() {
        val matchTables =
            ctx
                .fetch(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN ('campground_matches', 'campsite_matches')
                    ORDER BY table_name
                    """.trimIndent(),
                ).map { it.get("table_name", String::class.java) }
        val matchColumns =
            ctx
                .fetch(
                    """
                    SELECT table_name || '.' || column_name AS ref
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND (
                        (table_name = 'campgrounds' AND column_name IN ('match_group_id', 'preferred_availability_source'))
                        OR (table_name = 'campsites' AND column_name = 'match_group_id')
                      )
                    ORDER BY ref
                    """.trimIndent(),
                ).map { it.get("ref", String::class.java) }

        assertEquals(emptyList<String>(), matchTables)
        assertEquals(emptyList<String>(), matchColumns)
    }

    @Test
    fun `data_provider and data_provider_ref columns are non-null`() {
        val providerRows =
            ctx
                .fetch(
                    """
                    SELECT table_name || '.' || column_name || ':' || is_nullable AS ref
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND column_name IN ('data_provider', 'data_provider_ref')
                      AND table_name IN ('campgrounds', 'campsites')
                    ORDER BY table_name, column_name
                    """.trimIndent(),
                ).map { it.get("ref", String::class.java) }

        assertEquals(
            listOf(
                "campgrounds.data_provider:NO",
                "campgrounds.data_provider_ref:NO",
                "campsites.data_provider:NO",
                "campsites.data_provider_ref:NO",
            ),
            providerRows,
        )
    }

    @Test
    fun `vendor_refs and materialized views are removed`() {
        val oldTables =
            ctx
                .fetch(
                    """
                    SELECT table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name IN ('vendor_refs', 'campground_vendor_refs', 'campsite_vendor_refs')
                    ORDER BY table_name
                    """.trimIndent(),
                ).map { it.get("table_name", String::class.java) }

        val matviews =
            ctx
                .fetch(
                    """
                    SELECT matviewname
                    FROM pg_matviews
                    WHERE schemaname = 'public'
                      AND matviewname IN ('campground_canonical', 'campsite_canonical')
                    """.trimIndent(),
                ).map { it.get("matviewname", String::class.java) }

        assertEquals(emptyList<String>(), oldTables)
        assertEquals(emptyList<String>(), matviews)
    }

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
