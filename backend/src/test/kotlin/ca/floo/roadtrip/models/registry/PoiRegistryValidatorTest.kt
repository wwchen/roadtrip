package ca.floo.roadtrip.models.registry

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Validator tests for the three-section registry shape (RFC 0008 PR 2).
 * Asserts:
 *   - empty reservable_data + poi_reservable_joiner sections (the v0
 *     defaults) load fine on existing single-poi_data YAML.
 *   - reservable_data rows enforce the same etl-chain constraints as
 *     poi_data (slug uniqueness, no cross-row refs).
 *   - etl slugs across poi_data and reservable_data share one
 *     namespace; collisions across sections fail validation.
 *   - data_source slugs colliding with etl slugs in either section fail.
 *   - poi_reservable_joiner rows reject blank adapters and duplicate
 *     names but otherwise impose no chain constraints (no inputs,
 *     no etl chain).
 */
class PoiRegistryValidatorTest {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    private fun load(text: String): PoiRegistry = yaml.decodeFromString(PoiRegistry.serializer(), text).also { it.validate() }

    @Test
    fun `legacy single-poi_data YAML still loads`() {
        // No reservable_data or poi_reservable_joiner sections — should
        // default to empty lists and pass the validator unchanged.
        val r =
            load(
                """
                data_sources:
                  - slug: src-a
                    name: Source A
                    fetcher:
                      executor: python3
                      filename: scripts/x.py
                      output_dir_prefix: data/raw/src-a
                poi_data:
                  - name: A
                    category: campground
                    etls:
                      - slug: etl-a
                        adapter: AdapterA
                        inputs: [src-a]
                """.trimIndent(),
            )
        assertEquals(emptyList(), r.reservableData)
        assertEquals(emptyList(), r.poiReservableJoiners)
    }

    @Test
    fun `reservable_data row with valid etl chain loads`() {
        val r =
            load(
                """
                data_sources:
                  - slug: src-a
                    name: Source A
                    fetcher:
                      executor: python3
                      filename: scripts/x.py
                      output_dir_prefix: data/raw/src-a
                poi_data: []
                reservable_data:
                  - name: Federal Campsites
                    etls:
                      - slug: federal-campsites
                        adapter: RecGovCampsitesEtl
                        inputs: [src-a]
                """.trimIndent(),
            )
        assertEquals(1, r.reservableData.size)
        assertEquals("Federal Campsites", r.reservableData[0].name)
    }

    @Test
    fun `etl slugs across poi_data and reservable_data share one namespace`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    """
                    data_sources:
                      - slug: src-a
                        name: Source A
                        fetcher:
                          executor: python3
                          filename: scripts/x.py
                          output_dir_prefix: data/raw/src-a
                    poi_data:
                      - name: A
                        category: campground
                        etls:
                          - slug: shared-slug
                            adapter: AdapterA
                            inputs: [src-a]
                    reservable_data:
                      - name: B
                        etls:
                          - slug: shared-slug
                            adapter: AdapterB
                            inputs: [src-a]
                    """.trimIndent(),
                )
            }
        assertTrue(
            ex.message!!.contains("duplicate etl slug='shared-slug'"),
            "expected duplicate-slug error, got: ${ex.message}",
        )
    }

    @Test
    fun `data_source slug colliding with reservable_data etl slug fails`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    """
                    data_sources:
                      - slug: my-thing
                        name: Source
                        fetcher:
                          executor: python3
                          filename: scripts/x.py
                          output_dir_prefix: data/raw/my-thing
                    poi_data: []
                    reservable_data:
                      - name: B
                        etls:
                          - slug: my-thing
                            adapter: B
                            inputs: [my-thing]
                    """.trimIndent(),
                )
            }
        assertTrue(
            ex.message!!.contains("collides with a data_source slug"),
            "expected collision error, got: ${ex.message}",
        )
    }

    @Test
    fun `cross-row refs rejected within reservable_data section`() {
        // The same constraint poi_data already enforced — two
        // reservable_data rows can't share intermediate etl outputs.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    """
                    data_sources:
                      - slug: src-a
                        name: Source A
                        fetcher:
                          executor: python3
                          filename: scripts/x.py
                          output_dir_prefix: data/raw/src-a
                    poi_data: []
                    reservable_data:
                      - name: First
                        etls:
                          - slug: shared-intermediate
                            adapter: A
                            inputs: [src-a]
                      - name: Second
                        etls:
                          - slug: terminal
                            adapter: B
                            inputs: [shared-intermediate]
                    """.trimIndent(),
                )
            }
        assertTrue(
            ex.message!!.contains("cross-row refs not supported"),
            "expected cross-row error, got: ${ex.message}",
        )
    }

    @Test
    fun `cross-section refs rejected (reservable_data inputs cannot reference poi_data etls)`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    """
                    data_sources:
                      - slug: src-a
                        name: Source A
                        fetcher:
                          executor: python3
                          filename: scripts/x.py
                          output_dir_prefix: data/raw/src-a
                    poi_data:
                      - name: PoiRow
                        category: campground
                        etls:
                          - slug: poi-etl
                            adapter: A
                            inputs: [src-a]
                    reservable_data:
                      - name: ReservableRow
                        etls:
                          - slug: rsv-etl
                            adapter: B
                            inputs: [poi-etl]
                    """.trimIndent(),
                )
            }
        assertTrue(
            ex.message!!.contains("different section") || ex.message!!.contains("cross-section"),
            "expected cross-section error, got: ${ex.message}",
        )
    }

    @Test
    fun `poi_reservable_joiner row with adapter loads`() {
        val r =
            load(
                """
                data_sources: []
                poi_data: []
                reservable_data: []
                poi_reservable_joiner:
                  - name: Recgov join
                    adapter: RecgovPoiReservableJoiner
                """.trimIndent(),
            )
        assertEquals(1, r.poiReservableJoiners.size)
        assertEquals("RecgovPoiReservableJoiner", r.poiReservableJoiners[0].adapter)
    }

    @Test
    fun `joiner row with blank adapter fails`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    """
                    data_sources: []
                    poi_data: []
                    reservable_data: []
                    poi_reservable_joiner:
                      - name: Empty
                        adapter: ""
                    """.trimIndent(),
                )
            }
        assertTrue(
            ex.message!!.contains("empty adapter"),
            "expected empty-adapter error, got: ${ex.message}",
        )
    }

    @Test
    fun `joiner rows with duplicate names fail`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    """
                    data_sources: []
                    poi_data: []
                    reservable_data: []
                    poi_reservable_joiner:
                      - name: Dup
                        adapter: A
                      - name: Dup
                        adapter: B
                    """.trimIndent(),
                )
            }
        assertTrue(
            ex.message!!.contains("not unique"),
            "expected duplicate-name error, got: ${ex.message}",
        )
    }

    @Test
    fun `production poi-registry yaml validates`() {
        val file =
            java.io
                .File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        // Just confirms the live YAML doesn't regress. The test setup
        // path mirrors EtlOrchestratorTest's resolution.
        PoiRegistry.load(file)
    }
}
