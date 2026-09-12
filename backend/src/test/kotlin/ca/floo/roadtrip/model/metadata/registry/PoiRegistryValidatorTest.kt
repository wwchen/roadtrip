package ca.floo.roadtrip.model.metadata.registry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** YAML for an explicitly empty string — a present key with no name in it. */
private const val BLANK_DISPLAY_NAME = "display_name: \"\""
private const val BLANK_HOST = "host: \"\""
private const val BLANK_CODE = "- code: \"\""

/** Six-space list indent plus two: the column an `etls:` entry's own keys sit at. */
private const val ETL_KEY_INDENT = "        "

/** Two deeper again: the column an `args:` key sits at. */
private const val ARG_KEY_INDENT = "          "

/** The default `args` for the WA fixture row: exactly what the adapter accepts. */
@Suppress("TopLevelPropertyNaming")
private val WA_ARGS = listOf("host: washington.goingtocamp.com", "tenant: wa")

/** The production WA geometry block, the shape every positive fixture reuses. */
private const val WA_GEOMETRY_BLOCK =
    """
    geometry:
      sources:
        - input: uscampgrounds
          format: uscampgrounds_csv
          state: WA
      match:
        parent_fallback: true
    """

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
    @Test
    fun `legacy single-poi_data YAML still loads`() {
        // No campsite_data or poi_reservable_joiner sections — should
        // default to empty lists and pass the validator unchanged.
        val r =
            PoiRegistry.loadString(
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
            PoiRegistry.loadString(
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
                PoiRegistry.loadString(
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
            PoiRegistry.loadString(
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
                PoiRegistry.loadString(
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
                PoiRegistry.loadString(
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
                PoiRegistry.loadString(
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
                PoiRegistry.loadString(
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
                PoiRegistry.loadString(
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

    /**
     * A minimal one-row WA registry. [etlBody] is spliced in at the `etls:`
     * entry's own key column, so each geometry test varies only the block it
     * is about.
     */
    private fun waRegistry(
        etlBody: String,
        adapter: String = "AspiraCampgroundsEtl",
        args: List<String> = WA_ARGS,
    ): String =
        BOOKING_PROVIDERS + "\n" +
            """
            data_sources:
              - slug: aspira-maps-wa
                name: Aspira WA maps
                fetcher:
                  executor: python3
                  filename: scripts/fetch_aspira.py
                  output_dir_prefix: data/raw/aspira-maps-wa
              - slug: uscampgrounds
                name: uscampgrounds.info
                fetcher:
                  executor: python3
                  filename: scripts/fetch_uscampgrounds.py
                  output_dir_prefix: data/raw/uscampgrounds
            poi_data:
              - name: Washington State Parks
                category: campground
                agency: WA State Parks
                etls:
                  - slug: aspira-wa-campgrounds
                    adapter: $adapter
                    inputs: [aspira-maps-wa, uscampgrounds]
                    args:
            """.trimIndent() +
            args.joinToString("") { "\n" + ARG_KEY_INDENT + it } +
            etlBody.trimIndent().let { if (it.isBlank()) "" else "\n" + it.prependIndent(ETL_KEY_INDENT) } + "\n"

    private fun waError(
        etlBody: String,
        adapter: String = "AspiraCampgroundsEtl",
        args: List<String> = WA_ARGS,
    ): String =
        assertFailsWith<IllegalArgumentException> {
            PoiRegistry.loadString(waRegistry(etlBody, adapter, args))
        }.message!!

    @Test
    fun `a declared geometry block decodes to typed sources and match policy`() {
        val registry = PoiRegistry.loadString(waRegistry(WA_GEOMETRY_BLOCK))

        val geometry =
            registry.poiData
                .single()
                .etls
                .single()
                .geometry!!
        assertEquals(
            listOf(
                GeometrySourceSpec(
                    input = "uscampgrounds",
                    format = GeometryFormat.USCAMPGROUNDS_CSV,
                    state = "WA",
                ),
            ),
            geometry.sources,
        )
        assertEquals(MatchPolicy(parentFallback = true), geometry.match)
    }

    @Test
    fun `a geometry adapter declaring no geometry block fails`() {
        val expected =
            "poi_data 'Washington State Parks' etl 'aspira-wa-campgrounds' adapter 'AspiraCampgroundsEtl' " +
                "must declare 'geometry' with at least one source"
        assertTrue(waError("").contains(expected))

        // The other half of the same rule: a block that declares an empty list.
        val empty = waError("geometry:\n  sources: []")
        assertTrue(empty.contains(expected), empty)
    }

    @Test
    fun `a geometry source naming an input the etl does not declare fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: apca-places
                      format: arcgis_centroids
                """,
            )
        assertTrue(
            message.contains(
                "poi_data 'Washington State Parks' etl 'aspira-wa-campgrounds' " +
                    "geometry source input 'apca-places' is not one of the etl's inputs",
            ),
            message,
        )
    }

    @Test
    fun `the same geometry input declared twice fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                    - input: uscampgrounds
                      format: geojson_points
                """,
            )
        assertTrue(message.contains("declares geometry source input 'uscampgrounds' twice"), message)
    }

    @Test
    fun `state on a format that cannot honour it fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: geojson_points
                      state: WA
                """,
            )
        assertTrue(
            message.contains(
                "geometry source 'uscampgrounds' declares 'state', which only 'uscampgrounds_csv' honours",
            ),
            message,
        )
    }

    /** A blank filter matches nothing, so it would empty the join instead of narrowing it. */
    @Test
    fun `a blank state fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                      state: ""
                """,
            )
        assertTrue(message.contains("geometry source 'uscampgrounds' declares a blank 'state'"), message)
    }

    @Test
    fun `a blank name_property fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: geojson_points
                      name_property: ""
                """,
            )
        assertTrue(message.contains("geometry source 'uscampgrounds' declares a blank 'name_property'"), message)
    }

    @Test
    fun `name_property on a format that cannot honour it fails`() {
        val message =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                      name_property: Name_e
                """,
            )
        assertTrue(
            message.contains(
                "geometry source 'uscampgrounds' declares 'name_property', which only 'geojson_points' honours",
            ),
            message,
        )
    }

    @Test
    fun `a fuzzy threshold outside the open-zero-to-one range fails`() {
        val tooLow =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                  match:
                    fuzzy_threshold: 0.0
                """,
            )
        assertTrue(tooLow.contains("match.fuzzy_threshold=0.0 is outside (0.0, 1.0]"), tooLow)

        val tooHigh =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                  match:
                    fuzzy_threshold: 1.5
                """,
            )
        assertTrue(tooHigh.contains("match.fuzzy_threshold=1.5 is outside (0.0, 1.0]"), tooHigh)

        // NaN compares false to every bound, so a naive range check lets it boot
        // and then silently downgrades every fuzzy comparison to exact-only.
        val notANumber =
            waError(
                """
                geometry:
                  sources:
                    - input: uscampgrounds
                      format: uscampgrounds_csv
                  match:
                    fuzzy_threshold: .nan
                """,
            )
        assertTrue(notANumber.contains("match.fuzzy_threshold=NaN is outside (0.0, 1.0]"), notANumber)
    }

    /** The inclusive bound itself: nothing else pins `>` against `>=`. */
    @Test
    fun `a fuzzy threshold of exactly one is accepted`() {
        val registry =
            PoiRegistry.loadString(
                waRegistry(
                    """
                    geometry:
                      sources:
                        - input: uscampgrounds
                          format: uscampgrounds_csv
                      match:
                        fuzzy_threshold: 1.0
                    """,
                ),
            )

        assertEquals(
            MatchPolicy(fuzzyThreshold = 1.0),
            registry.poiData
                .single()
                .etls
                .single()
                .geometry!!
                .match,
        )
    }

    /** `CampflareCampgroundsEtl` has no adapter policy, so the WA args pass through it unjudged. */
    @Test
    fun `an adapter that joins no geometry may not declare a geometry block`() {
        val message =
            waError(
                etlBody = WA_GEOMETRY_BLOCK,
                adapter = "CampflareCampgroundsEtl",
            )
        assertTrue(
            message.contains(
                "adapter 'CampflareCampgroundsEtl' does not join geometry, so it must not declare 'geometry'",
            ),
            message,
        )
    }

    @Test
    fun `BcParksCampgroundsEtl must declare exactly one bcparks_strapi source`() {
        val expected =
            "adapter 'BcParksCampgroundsEtl' must declare exactly one geometry source with format 'bcparks_strapi'"
        val wrongFormat =
            waError(
                etlBody =
                    """
                    geometry:
                      sources:
                        - input: uscampgrounds
                          format: uscampgrounds_csv
                    """,
                adapter = "BcParksCampgroundsEtl",
            )
        assertTrue(wrongFormat.contains(expected), wrongFormat)

        // "Exactly one" is the other half: the right format, twice over, is still wrong.
        val twoSources =
            waError(
                etlBody =
                    """
                    geometry:
                      sources:
                        - input: uscampgrounds
                          format: bcparks_strapi
                        - input: aspira-maps-wa
                          format: bcparks_strapi
                    """,
                adapter = "BcParksCampgroundsEtl",
            )
        assertTrue(twoSources.contains(expected), twoSources)
    }

    /** The dead `parent_name_fallback` arg's whole failure class, now a boot error. */
    @Test
    fun `an args key the adapter does not accept fails`() {
        val message =
            waError(
                etlBody = WA_GEOMETRY_BLOCK,
                args = WA_ARGS + "parent_name_fallback: true",
            )
        assertTrue(
            message.contains(
                "adapter 'AspiraCampgroundsEtl' does not accept arg 'parent_name_fallback' (accepted: host, tenant)",
            ),
            message,
        )
    }

    @Test
    fun `strict decoding rejects an unknown key inside the geometry block`() {
        val err =
            assertFailsWith<Exception> {
                PoiRegistry.loadString(
                    waRegistry(
                        """
                        geometry:
                          sources:
                            - input: uscampgrounds
                              format: uscampgrounds_csv
                          matcher:
                            parent_fallback: true
                        """,
                    ),
                )
            }
        assertTrue(err.message!!.contains("matcher"), err.message)
    }

    /** The shipped rows, decoded. This is the test the production YAML edit has to satisfy. */
    @Test
    fun `the three shipped geometry rows decode to their declared policies`() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val bySlug = registry.poiData.flatMap { it.etls }.associateBy { it.slug }

        assertEquals(
            GeometryPolicy(
                sources = listOf(GeometrySourceSpec("uscampgrounds", GeometryFormat.USCAMPGROUNDS_CSV, state = "WA")),
                match = MatchPolicy(parentFallback = true),
            ),
            bySlug.getValue("aspira-wa-campgrounds").geometry,
        )
        assertEquals(
            GeometryPolicy(
                sources = listOf(GeometrySourceSpec("bcparks-strapi", GeometryFormat.BCPARKS_STRAPI)),
                match = MatchPolicy(parentFallback = true),
            ),
            bySlug.getValue("aspira-bc-campgrounds").geometry,
        )
        assertEquals(
            GeometryPolicy(
                sources =
                    listOf(
                        GeometrySourceSpec("apca-accommodation", GeometryFormat.GEOJSON_POINTS, nameProperty = "Name_e"),
                        GeometrySourceSpec("apca-places", GeometryFormat.ARCGIS_CENTROIDS),
                    ),
                match = MatchPolicy(parentFallback = true),
            ),
            bySlug.getValue("aspira-pc-campgrounds").geometry,
        )
        assertEquals(mapOf("host" to "reservation.pc.gc.ca", "tenant" to "pc"), bySlug.getValue("aspira-pc-campgrounds").args)
    }

    /**
     * The one invariant a single adapter table cannot enforce by construction: a
     * policy that requires an arg its own closed set does not accept would fail
     * every row twice over, once for the missing key and once for the extra one.
     */
    @Test
    fun `every adapter policy accepts the args its own tenant and host rules require`() {
        for ((adapter, policy) in ADAPTER_POLICIES) {
            val accepted = policy.acceptedArgKeys ?: continue
            assertTrue(
                accepted.containsAll(policy.requiredArgKeys),
                "$adapter requires ${policy.requiredArgKeys - accepted} but does not accept them",
            )
        }
    }
}
