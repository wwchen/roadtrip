package ca.floo.roadtrip.model.metadata.registry

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** YAML for an explicitly empty string — a present key with no name in it. */
private const val BLANK_DISPLAY_NAME = "display_name: \"\""
private const val BLANK_HOST = "host: \"\""
private const val BLANK_CODE = "- code: \"\""

/** The shipped booking_providers section. Every fixture needs it: validate()
 *  requires one row per BookingProvider member. */
@Suppress("TopLevelPropertyNaming")
private val BOOKING_PROVIDERS =
    """
    booking_providers:
      - id: recgov
        display_name: Recreation.gov
        sells: true
        tenants:
          - host: www.recreation.gov
      - id: campflare
        display_name: Campflare
        sells: false
        tenants:
          - host: campflare.com
      - id: aspira
        display_name: Aspira NextGen
        sells: true
        tenants:
          - code: pc
            host: reservation.pc.gc.ca
            display_name: Parks Canada
          - code: bc
            host: camping.bcparks.ca
            display_name: BC Parks
          - code: wa
            host: washington.goingtocamp.com
            display_name: Washington State Parks
      - id: reserveamerica
        display_name: ReserveAmerica
        sells: true
        tenants:
          - code: ABPP
            host: shop.albertaparks.ca
            display_name: Alberta Parks
          - code: NY
            host: newyorkstateparks.reserveamerica.com
            display_name: New York State Parks
      - id: reservecalifornia
        display_name: ReserveCalifornia
        sells: true
        tenants:
          - host: www.reservecalifornia.com
    """.trimIndent()

/**
 * Validator tests for the three-section registry shape (RFC 0008 PR 2) plus
 * the booking_providers section.
 * Asserts:
 *   - empty campsite_data sections load fine on existing single-poi_data YAML.
 *   - campsite_data rows enforce the same terminal ETL constraints as
 *     poi_data.
 *   - etl slugs across poi_data and campsite_data share one
 *     namespace; collisions across sections fail validation.
 *   - data_source slugs colliding with etl slugs in either section fail.
 *   - booking_providers covers every vendor, has unique hosts, and agrees
 *     with the tenant args ETL rows declare.
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
                BOOKING_PROVIDERS + "\n" +
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
    }

    @Test
    fun `poi_data agency accepts scalar constants and derived field mappings`() {
        val r =
            load(
                BOOKING_PROVIDERS + "\n" +
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
                      - name: Rec.gov Campgrounds
                        category: campground
                        agency:
                          derived_from_field: ORGANIZATION[0].OrgName
                        etls:
                          - slug: recgov-campgrounds
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
                    BOOKING_PROVIDERS + "\n" +
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
    fun `campsite_data row with valid terminal etl loads`() {
        val r =
            load(
                BOOKING_PROVIDERS + "\n" +
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
                      - name: Rec.gov Campsites
                        etls:
                          - slug: recgov-campsites
                            adapter: RecGovCampsitesEtl
                            inputs: [src-a]
                    """.trimIndent(),
            )
        assertEquals(1, r.campsiteData.size)
        assertEquals("Rec.gov Campsites", r.campsiteData[0].name)
    }

    @Test
    fun `poi_data rows reject intermediate etl chains`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    BOOKING_PROVIDERS + "\n" +
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
                              - slug: stage-one
                                adapter: A
                                inputs: [src-a]
                              - slug: stage-two
                                adapter: B
                                inputs: [stage-one]
                        """.trimIndent(),
                )
            }

        assertTrue(
            ex.message!!.contains("must declare exactly one etl"),
            "expected single-etl error, got: ${ex.message}",
        )
    }

    @Test
    fun `etl slugs across poi_data and campsite_data share one namespace`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    BOOKING_PROVIDERS + "\n" +
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
                    BOOKING_PROVIDERS + "\n" +
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
    fun `etl inputs cannot reference a different row etl`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    BOOKING_PROVIDERS + "\n" +
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
            ex.message!!.contains("inputs 'shared-intermediate' which is not a data_source"),
            "expected non-data-source error, got: ${ex.message}",
        )
    }

    @Test
    fun `cross-section refs rejected (campsite_data inputs cannot reference poi_data etls)`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                load(
                    BOOKING_PROVIDERS + "\n" +
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
            ex.message!!.contains("inputs 'poi-etl' which is not a data_source"),
            "expected non-data-source error, got: ${ex.message}",
        )
    }

    @Test
    fun `production poi-registry resource validates`() {
        PoiRegistry.loadResource("poi-registry.yaml")
    }

    @Test
    fun `a missing booking_providers row fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace(
                        "  - id: campflare\n    display_name: Campflare\n    sells: false\n    tenants:\n      - host: campflare.com\n",
                        "",
                    ) +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("booking_providers is missing a row for 'campflare'"), err.message)
    }

    @Test
    fun `a duplicate booking_providers host fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("host: camping.bcparks.ca", "host: www.recreation.gov") +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("duplicate booking_providers host 'recreation.gov'"), err.message)
    }

    @Test
    fun `two tenants of one vendor sharing a code fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("      - code: wa\n", "      - code: bc\n") +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("booking_providers 'aspira' has duplicate tenant code 'bc'"), err.message)
    }

    @Test
    fun `an etl args tenant naming no tenant fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS +
                        "\n" +
                        """
                        data_sources:
                          - slug: aspira-maps-zz
                            name: Aspira ZZ maps
                            fetcher:
                              executor: python3
                              filename: scripts/fetch_aspira.py
                              output_dir_prefix: data/raw/aspira-maps-zz
                        poi_data:
                          - name: Zed Parks
                            category: campground
                            agency: Zed Parks
                            etls:
                              - slug: aspira-zz-campgrounds
                                adapter: AspiraCampgroundsEtl
                                inputs: [aspira-maps-zz]
                                args:
                                  tenant: zz
                                  host: zz.goingtocamp.com
                        """.trimIndent(),
                )
            }
        assertTrue(
            err.message!!.contains("poi_data 'Zed Parks' etl 'aspira-zz-campgrounds' args.tenant='zz' is not a tenant of 'aspira'"),
            err.message,
        )
    }

    @Test
    fun `an AspiraCampgroundsEtl row without args tenant fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS +
                        "\n" +
                        """
                        data_sources:
                          - slug: aspira-maps-bc
                            name: Aspira BC maps
                            fetcher:
                              executor: python3
                              filename: scripts/fetch_aspira.py
                              output_dir_prefix: data/raw/aspira-maps-bc
                        poi_data:
                          - name: BC Provincial Parks
                            category: campground
                            agency: BC Parks
                            etls:
                              - slug: aspira-bc-campgrounds
                                adapter: AspiraCampgroundsEtl
                                inputs: [aspira-maps-bc]
                                args:
                                  host: camping.bcparks.ca
                        """.trimIndent(),
                )
            }
        assertTrue(
            err.message!!.contains(
                "poi_data 'BC Provincial Parks' etl 'aspira-bc-campgrounds' adapter 'AspiraCampgroundsEtl' " +
                    "is missing required arg 'tenant'",
            ),
            err.message,
        )
    }

    /**
     * The tenant cross-check used to be skipped whenever *any* earlier loop had
     * already found something, so an operator fixed the slug, redeployed, and
     * only then learned about the tenant typo. One boot, both errors.
     */
    @Test
    fun `an unrelated registry error does not hide the tenant cross-check`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS +
                        "\n" +
                        """
                        data_sources:
                          - slug: aspira-maps-zz
                            name: Aspira ZZ maps
                            fetcher:
                              executor: python3
                              filename: scripts/fetch_aspira.py
                              output_dir_prefix: data/raw/aspira-maps-zz
                          - slug: aspira-maps-zz
                            name: Aspira ZZ maps again
                            fetcher:
                              executor: python3
                              filename: scripts/fetch_aspira.py
                              output_dir_prefix: data/raw/aspira-maps-zz-2
                        poi_data:
                          - name: Zed Parks
                            category: campground
                            agency: Zed Parks
                            etls:
                              - slug: aspira-zz-campgrounds
                                adapter: AspiraCampgroundsEtl
                                inputs: [aspira-maps-zz]
                                args:
                                  tenant: zz
                                  host: zz.goingtocamp.com
                        """.trimIndent(),
                )
            }

        assertTrue(err.message!!.contains("duplicate data_source slug='aspira-maps-zz'"), err.message)
        assertTrue(err.message!!.contains("args.tenant='zz' is not a tenant of 'aspira'"), err.message)
    }

    @Test
    fun `a booking vendor with no tenants fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("    tenants:\n      - host: www.recreation.gov\n", "") +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("booking_providers 'recgov' declares no tenants"), err.message)
    }

    @Test
    fun `a blank vendor display_name fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("display_name: Campflare", BLANK_DISPLAY_NAME) +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("booking_providers 'campflare' has a blank display_name"), err.message)
    }

    @Test
    fun `a blank tenant display_name fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("display_name: BC Parks", BLANK_DISPLAY_NAME) +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("booking_providers 'aspira' tenant 'bc' has a blank display_name"), err.message)
    }

    @Test
    fun `a blank tenant host fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("host: campflare.com", BLANK_HOST) +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("booking_providers 'campflare' tenant 'null' has a blank host"), err.message)
    }

    /** Absent is how a single-tenant vendor says "no code"; blank is a key nothing stores. */
    @Test
    fun `a blank tenant code fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS.replace("- code: bc", BLANK_CODE) +
                        "\n" +
                        """
                        data_sources: []
                        poi_data: []
                        """.trimIndent(),
                )
            }
        assertTrue(err.message!!.contains("booking_providers 'aspira' has a blank tenant code"), err.message)
    }

    /** `AspiraCampgroundsEtl.transform` errors without it, so boot is where it belongs. */
    @Test
    fun `an AspiraCampgroundsEtl row without args host fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS +
                        "\n" +
                        """
                        data_sources:
                          - slug: aspira-maps-bc
                            name: Aspira BC maps
                            fetcher:
                              executor: python3
                              filename: scripts/fetch_aspira.py
                              output_dir_prefix: data/raw/aspira-maps-bc
                        poi_data:
                          - name: BC Provincial Parks
                            category: campground
                            agency: BC Parks
                            etls:
                              - slug: aspira-bc-campgrounds
                                adapter: AspiraCampgroundsEtl
                                inputs: [aspira-maps-bc]
                                args:
                                  tenant: bc
                        """.trimIndent(),
                )
            }
        assertTrue(
            err.message!!.contains(
                "poi_data 'BC Provincial Parks' etl 'aspira-bc-campgrounds' adapter 'AspiraCampgroundsEtl' " +
                    "is missing required arg 'host'",
            ),
            err.message,
        )
    }

    @Test
    fun `an etl args host disagreeing with its tenant fails`() {
        val err =
            assertFailsWith<IllegalArgumentException> {
                PoiRegistry.loadString(
                    BOOKING_PROVIDERS +
                        "\n" +
                        """
                        data_sources:
                          - slug: aspira-maps-bc
                            name: Aspira BC maps
                            fetcher:
                              executor: python3
                              filename: scripts/fetch_aspira.py
                              output_dir_prefix: data/raw/aspira-maps-bc
                        poi_data:
                          - name: BC Provincial Parks
                            category: campground
                            agency: BC Parks
                            etls:
                              - slug: aspira-bc-campgrounds
                                adapter: BcParksCampgroundsEtl
                                inputs: [aspira-maps-bc]
                                args:
                                  tenant: bc
                                  host: camping.example.test
                        """.trimIndent(),
                )
            }
        assertTrue(
            err.message!!.contains(
                "poi_data 'BC Provincial Parks' etl 'aspira-bc-campgrounds' args.host='camping.example.test' " +
                    "does not match tenant 'bc' host 'camping.bcparks.ca'",
            ),
            err.message,
        )
    }
}
