package ca.floo.roadtrip.models.metadata.registry

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Validator tests for the three-section registry shape (RFC 0008 PR 2).
 * Asserts:
 *   - empty campsite_data + poi_reservable_joiner sections (the v0
 *     defaults) load fine on existing single-poi_data YAML.
 *   - campsite_data rows enforce the same etl-chain constraints as
 *     poi_data (slug uniqueness, no cross-row refs).
 *   - etl slugs across poi_data and campsite_data share one
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
        // No campsite_data or poi_reservable_joiner sections — should
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
        assertEquals(emptyList(), r.campsiteData)
        assertEquals(emptyList(), r.poiReservableJoiners)
    }

    @Test
    fun `poi_data agency accepts scalar constants and derived field mappings`() {
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
                  - slug: src-b
                    name: Source B
                    fetcher:
                      executor: python3
                      filename: scripts/y.py
                      output_dir_prefix: data/raw/src-b
                poi_data:
                  - name: Federal Campgrounds
                    category: campground
                    agency:
                      derived_from_field: ORGANIZATION[0].OrgName
                    etls:
                      - slug: federal-campgrounds
                        adapter: RecGovCampgroundsEtl
                        inputs: [src-a]
                  - name: Planet Fitness
                    category: planet-fitness
                    agency: Planet Fitness
                    etls:
                      - slug: planet-fitness
                        adapter: PlanetFitnessEtl
                        inputs: [src-b]
                """.trimIndent(),
            )

        assertEquals(AgencyConfig.DerivedFromField("ORGANIZATION[0].OrgName"), r.poiData[0].agency)
        assertEquals(AgencyConfig.Constant("Planet Fitness"), r.poiData[1].agency)
    }

    @Test
    fun `poi_data agency rejects blank scalar constants`() {
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
                      - name: Blank Agency
                        category: planet-fitness
                        agency: ""
                        etls:
                          - slug: blank-agency
                            adapter: PlanetFitnessEtl
                            inputs: [src-a]
                    """.trimIndent(),
                )
            }

        assertTrue(
            ex.message!!.contains("agency must not be blank"),
            "expected blank-agency error, got: ${ex.message}",
        )
    }

    @Test
    fun `campsite_data row with valid etl chain loads`() {
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
                campsite_data:
                  - name: Federal Campsites
                    etls:
                      - slug: federal-campsites
                        adapter: RecGovCampsitesEtl
                        inputs: [src-a]
                """.trimIndent(),
            )
        assertEquals(1, r.campsiteData.size)
        assertEquals("Federal Campsites", r.campsiteData[0].name)
    }

    @Test
    fun `etl slugs across poi_data and campsite_data share one namespace`() {
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
                    campsite_data:
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
    fun `data_source slug colliding with campsite_data etl slug fails`() {
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
                    campsite_data:
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
    fun `cross-row refs rejected within campsite_data section`() {
        // The same constraint poi_data already enforced — two
        // campsite_data rows can't share intermediate etl outputs.
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
                    campsite_data:
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
    fun `cross-section refs rejected (campsite_data inputs cannot reference poi_data etls)`() {
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
                    campsite_data:
                      - name: CampsiteRow
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
                campsite_data: []
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
                    campsite_data: []
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
                    campsite_data: []
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

    @Test
    fun `reserveamerica provider tenants are read from terminal etl args`() {
        val r =
            load(
                """
                data_sources:
                  - slug: reserveamerica-test
                    name: ReserveAmerica test
                    fetcher:
                      executor: python3
                      filename: scripts/x.py
                      output_dir_prefix: data/raw/reserveamerica-test
                poi_data:
                  - name: Test ReserveAmerica Parks
                    category: campground
                    etls:
                      - slug: test-reserveamerica-parks
                        adapter: ReserveAmericaEtl
                        inputs: [reserveamerica-test]
                        args:
                          contract: ZZ
                          host: example.reserveamerica.test
                          booking_horizon_days: "123"
                          provider: reserveamerica
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                ReserveAmericaSourceConfig(
                    source = "test-reserveamerica-parks",
                    host = "example.reserveamerica.test",
                    contractCode = "ZZ",
                    bookingHorizonDays = 123,
                ),
            ),
            r.reserveAmericaSources(),
        )
    }
}
