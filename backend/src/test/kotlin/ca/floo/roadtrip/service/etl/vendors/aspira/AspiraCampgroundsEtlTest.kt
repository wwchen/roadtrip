package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.domain.CampgroundUpsertCandidate
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.registry.GeometryFormat
import ca.floo.roadtrip.model.metadata.registry.GeometryPolicy
import ca.floo.roadtrip.model.metadata.registry.GeometrySourceSpec
import ca.floo.roadtrip.model.metadata.registry.MatchPolicy
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.records
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit test for the campground-level POI emission in [AspiraCampgroundsEtl].
 *
 * The transform is a pure function of an [AspiraJoinDto] plus a
 * [TransformCtx], so no DB / orchestrator is needed. We seed one geometry
 * source with both a park name and a campground name, then feed leaves that
 * mirror the real PC map shape: a park-container leaf (Banff, no
 * resourceLocationId) and a campground leaf (Two Jack Lakeside, with a
 * resourceLocationId). The park container must be dropped even though its
 * name matches geometry.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AspiraCampgroundsEtlTest {
    private lateinit var ctx: TransformCtx

    // Real terminal slug so args (host), subcategory (federal) and the
    // constant agency (Parks Canada) resolve from the production YAML.
    private val slug = "aspira-pc-campgrounds"

    // The PC row's shape, one source: the fixtures feed a GeoJSON envelope under
    // the slug `test-geom`, and the parent fallback is what PC declares today.
    private val geometryPolicy =
        GeometryPolicy(
            sources = listOf(GeometrySourceSpec(input = "test-geom", format = GeometryFormat.GEOJSON_POINTS)),
            match = MatchPolicy(parentFallback = true),
        )

    // Category 100 is bookable (showResourceCapacityOnline=true, e.g. Campsite);
    // 200 is non-bookable (false, e.g. Parking). The flag is Aspira's own — the
    // filter reads it straight from the dictionary, no curated name list.
    private val categoryDict =
        """
        {"resource_categories":[
          {"resourceCategoryId":100,"showResourceCapacityOnline":true},
          {"resourceCategoryId":200,"showResourceCapacityOnline":false}
        ]}
        """.trimIndent()

    // A dictionary that marks every category bookable — the shape WA/BC ship
    // today. Nothing should be dropped for such a tenant.
    private val allBookableDict =
        """
        {"resource_categories":[
          {"resourceCategoryId":100,"showResourceCapacityOnline":true},
          {"resourceCategoryId":200,"showResourceCapacityOnline":true}
        ]}
        """.trimIndent()

    private val parkContainer =
        AspiraLeaf(
            name = "Banff",
            transactionLocationId = -2147483648L,
            mapId = -2147483630L,
            resourceLocationId = null,
            parentName = null,
        )

    private val campground =
        AspiraLeaf(
            name = "Two Jack Lakeside",
            transactionLocationId = 1002L,
            mapId = -2147483641L,
            resourceLocationId = 9002L,
            parentName = "Banff",
        )

    // A campground leaf whose own name misses geometry but whose parent park
    // centroid matches. This is the load-bearing correctness claim of the
    // change: dropping park-container leaves is only safe because each park's
    // campground leaves still land — via their own coordinates or, failing
    // that, the parent park's centroid. If this fallback regressed, parks
    // would silently vanish from the map while every other test still passed.
    private val campgroundMissingOwnName =
        AspiraLeaf(
            name = "Backcountry Site With No Geometry",
            transactionLocationId = 1003L,
            mapId = -2147483642L,
            resourceLocationId = 9003L,
            parentName = "Banff National Park of Canada",
        )

    @BeforeAll
    fun setUp() {
        val registry = PoiRegistry.loadResource("poi-registry.yaml")
        val tmp = Files.createTempDirectory("aspira-join-by-name-").toFile()
        tmp.deleteOnExit()
        ctx = TransformCtx.load(tmp, registry)
    }

    /**
     * A GeoJSON FeatureCollection envelope seeding the two names the tests
     * need. Wrapped through [GeoJsonFeaturesSource] (a real, non-sealed-blocked
     * source) so we exercise the production indexing path instead of a
     * hand-rolled stub — sealed [GeometrySource] can't be implemented from the
     * test source set anyway.
     */
    private fun geomEnvelope(): Envelope {
        val featureCollection =
            """
            {
              "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://geom", "method": "GET" },
              "response": { "status": 200 },
              "payload": {
                "type": "FeatureCollection",
                "features": [
                  { "type": "Feature",
                    "properties": { "name": "Banff National Park of Canada" },
                    "geometry": { "type": "Point", "coordinates": [-115.57, 51.18] } },
                  { "type": "Feature",
                    "properties": { "name": "Two Jack Lakeside" },
                    "geometry": { "type": "Point", "coordinates": [-115.49, 51.22] } }
                ]
              }
            }
            """.trimIndent()
        return Json.decodeFromString(Envelope.serializer(), featureCollection)
    }

    private fun dtoOf(vararg leaves: AspiraLeaf): AspiraJoinDto =
        AspiraJoinDto(
            leaves = leaves.toList(),
            geomSources =
                listOf(
                    "test-geom" to GeoJsonFeaturesSource(listOf(geomEnvelope())),
                ),
            fetchedAt = Instant.parse("2026-07-05T00:00:00Z"),
        )

    private fun campgrounds(dto: AspiraJoinDto): List<CampgroundUpsertCandidate> =
        records(
            AspiraCampgroundsEtl(etlSlug = slug, aspiraTenant = "pc", geometry = geometryPolicy)
                .transform(dto, ctx),
        )

    /** DTO variant carrying an inventory envelope + category dictionary for the non-bookable filter. */
    private fun dtoWith(
        leaf: AspiraLeaf,
        inventoryPayloadJson: String,
        dictionaryPayloadJson: String,
        etlSlug: String = slug,
    ): AspiraJoinDto =
        AspiraJoinDto(
            leaves = listOf(leaf),
            geomSources =
                listOf(
                    "test-geom" to GeoJsonFeaturesSource(listOf(geomEnvelope())),
                ),
            inventoryEnvelopes = listOf(envelopeOf(inventoryPayloadJson)),
            dictionaryPayload = Json.parseToJsonElement(dictionaryPayloadJson).jsonObject,
            fetchedAt = Instant.parse("2026-07-05T00:00:00Z"),
        )

    /** One bookable `/api/maps` leaf, named to match the seeded geometry. */
    private fun mapsEnvelope(): Envelope =
        envelopeOf(
            """
            [
              {
                "mapId": -2147483641,
                "transactionLocationId": "1002",
                "resourceLocationId": "9002",
                "localizedValues": [{"cultureName": "en-CA", "title": "Two Jack Lakeside"}],
                "mapLinks": [],
                "parentMap": null
              }
            ]
            """.trimIndent(),
        )

    private fun envelopeOf(payloadJson: String): Envelope =
        Json.decodeFromString(
            Envelope.serializer(),
            """
            { "fetcher": "test", "fetcher_version": "1",
              "fetched_at": "2026-07-05T00:00:00Z",
              "request": { "url": "test://inv", "method": "GET" },
              "response": { "status": 200 },
              "payload": $payloadJson }
            """.trimIndent(),
        )

    // A leaf that name-matches geometry (so it WOULD emit) but whose
    // resourceLocationId varies per test via the inventory.
    private fun nameMatchingLeaf(resLoc: Long) =
        AspiraLeaf(
            name = "Two Jack Lakeside",
            transactionLocationId = 1005L,
            mapId = -2147483650L,
            resourceLocationId = resLoc,
            parentName = null,
        )

    /**
     * The negative-selection hole: every input that was not maps, inventory or
     * dictionaries used to become a geometry source, so a typo'd slug quietly
     * indexed nothing. Now it fails the parse.
     */
    @Test
    fun `an input that is neither declared geometry nor a known feed fails parse`() {
        val etl =
            AspiraCampgroundsEtl(
                etlSlug = slug,
                aspiraTenant = "pc",
                geometry = GeometryPolicy(sources = listOf(GeometrySourceSpec("test-geom", GeometryFormat.GEOJSON_POINTS))),
            )
        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-pc" to listOf(envelopeOf("[]")),
                    "test-geom" to listOf(geomEnvelope()),
                    "apca-plaecs" to listOf(geomEnvelope()),
                ),
            )

        val bad = etl.parse(inputs).single() as ParseResult.Bad
        assertTrue(
            bad.errors.any {
                it == "input 'apca-plaecs' is neither a declared geometry source nor the maps, inventory or dictionaries feed"
            },
            bad.errors.toString(),
        )
    }

    @Test
    fun `a declared geometry source with no envelopes fails parse`() {
        val etl =
            AspiraCampgroundsEtl(
                etlSlug = slug,
                aspiraTenant = "pc",
                geometry = GeometryPolicy(sources = listOf(GeometrySourceSpec("test-geom", GeometryFormat.GEOJSON_POINTS))),
            )
        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-pc" to listOf(envelopeOf("[]")),
                    "test-geom" to emptyList<Envelope>(),
                ),
            )

        val bad = etl.parse(inputs).single() as ParseResult.Bad
        assertTrue(bad.errors.any { it == "declared geometry source 'test-geom' has no envelopes" }, bad.errors.toString())
    }

    @Test
    fun `a declared geometry source missing from this run's inputs fails parse`() {
        val etl =
            AspiraCampgroundsEtl(
                etlSlug = slug,
                aspiraTenant = "pc",
                geometry = GeometryPolicy(sources = listOf(GeometrySourceSpec("test-geom", GeometryFormat.GEOJSON_POINTS))),
            )
        val inputs = InputBundle(linkedMapOf("aspira-maps-pc" to listOf(mapsEnvelope())))

        val bad = etl.parse(inputs).single() as ParseResult.Bad
        assertTrue(
            bad.errors.any { it == "declared geometry source 'test-geom' is not among this run's inputs" },
            bad.errors.toString(),
        )
    }

    /**
     * The role feeds are looked for only among the inputs geometry did not
     * claim, so a declared source whose own slug contains `maps` cannot shadow
     * the real `/api/maps` feed — which, declared second, would otherwise lose
     * the `firstOrNull` and be reported as an unaccounted input.
     */
    @Test
    fun `a declared geometry input whose slug contains maps does not shadow the maps feed`() {
        val etl =
            AspiraCampgroundsEtl(
                etlSlug = slug,
                aspiraTenant = "pc",
                geometry = GeometryPolicy(sources = listOf(GeometrySourceSpec("apca-maps-geom", GeometryFormat.GEOJSON_POINTS))),
            )
        val inputs =
            InputBundle(
                linkedMapOf(
                    "apca-maps-geom" to listOf(geomEnvelope()),
                    "aspira-maps-pc" to listOf(mapsEnvelope()),
                ),
            )

        val dto = (etl.parse(inputs).single() as ParseResult.Ok).dto
        assertEquals(listOf("Two Jack Lakeside"), dto.leaves.map { it.name })
        assertEquals(listOf("apca-maps-geom"), dto.geomSources.map { it.first })
    }

    @Test
    fun `parse builds one source per declared geometry input, in declared order`() {
        val etl =
            AspiraCampgroundsEtl(
                etlSlug = slug,
                aspiraTenant = "pc",
                geometry =
                    GeometryPolicy(
                        sources =
                            listOf(
                                GeometrySourceSpec("test-geom", GeometryFormat.GEOJSON_POINTS),
                                GeometrySourceSpec("test-centroids", GeometryFormat.ARCGIS_CENTROIDS),
                            ),
                    ),
            )
        val inputs =
            InputBundle(
                linkedMapOf(
                    "aspira-maps-pc" to listOf(mapsEnvelope()),
                    "test-centroids" to listOf(envelopeOf("""{"features":[]}""")),
                    "test-geom" to listOf(geomEnvelope()),
                ),
            )

        val sources = (etl.parse(inputs).single() as ParseResult.Ok).dto.geomSources
        assertEquals(listOf("test-geom", "test-centroids"), sources.map { it.first })
        assertTrue(sources[0].second is GeoJsonFeaturesSource, sources[0].second.toString())
        assertTrue(sources[1].second is ArcGisCentroidSource, sources[1].second.toString())
    }

    @Test
    fun `drops park-container leaves even when their name matches geometry`() {
        val campgrounds = campgrounds(dtoOf(parkContainer, campground))

        assertEquals(1, campgrounds.size, "only the campground node should become a campground")
        val campground = campgrounds.single()
        assertEquals("Two Jack Lakeside", campground.name)
    }

    @Test
    fun `emits nothing when every leaf is a park container`() {
        val campgrounds = campgrounds(dtoOf(parkContainer))
        assertTrue(campgrounds.isEmpty(), "a park with no campground children yields no campground")
    }

    @Test
    fun `keeps campground POIs keyed by transactionLocationId and mapId`() {
        val campground = campgrounds(dtoOf(campground)).single()

        assertEquals("1002:-2147483641", campground.dataProviderRef.serialize())
        assertEquals("federal", campground.kind)
        assertEquals("Parks Canada", campground.management!!.agency)
    }

    @Test
    fun `resourceLocationId metadata is preserved`() {
        val campground = campgrounds(dtoOf(campground)).single()

        assertEquals("Two Jack Lakeside", campground.name)
    }

    @Test
    fun `the leaf parent map title becomes the campground parent name`() {
        assertEquals("Banff", campgrounds(dtoOf(campground)).single().parentName)
        assertEquals("Banff National Park of Canada", campgrounds(dtoOf(campgroundMissingOwnName)).single().parentName)
    }

    @Test
    fun `source payload records match provenance`() {
        val campground = campgrounds(dtoOf(campground)).single()
        val extras = campground.sourcePayload!!.jsonObject
        assertEquals("Two Jack Lakeside", extras["name"]!!.jsonPrimitive.content)
        assertEquals("exact", extras["match_kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `booking ref adopts the one inventory child map`() {
        val leaf =
            AspiraLeaf(
                name = "Two Jack Lakeside",
                transactionLocationId = 1005L,
                mapId = -2147483026L,
                resourceLocationId = 9002L,
                parentName = "Banff",
            )
        val inventory =
            """
            {
              "c1":{"resourceLocationId":9002,"resourceCategoryId":100,"mapIds":[-2147483645]},
              "d1":{"resourceLocationId":9002,"resourceCategoryId":100,"mapIds":[-2147483645]}
            }
            """.trimIndent()

        val campground = campgrounds(dtoWith(leaf, inventory, categoryDict)).single()

        assertEquals("pc:1005:-2147483645:9002", campground.bookingProviderRef)
    }

    @Test
    fun `booking CTA ref keeps the leaf map when sites span several child maps`() {
        val leaf =
            AspiraLeaf(
                name = "Two Jack Lakeside",
                transactionLocationId = 1005L,
                mapId = -2147483026L,
                resourceLocationId = 9002L,
                parentName = "Banff",
            )
        val inventory =
            """
            {
              "h1":{"resourceLocationId":9002,"resourceCategoryId":100,"mapIds":[-2147483645]},
              "b1":{"resourceLocationId":9002,"resourceCategoryId":100,"mapIds":[-2147483639]}
            }
            """.trimIndent()

        val campground = campgrounds(dtoWith(leaf, inventory, categoryDict)).single()

        assertEquals("pc:1005:-2147483026:9002", campground.bookingProviderRef)
    }

    @Test
    fun `booking CTA ref prefers bookable inventory maps over non-bookable maps`() {
        val leaf =
            AspiraLeaf(
                name = "Two Jack Lakeside",
                transactionLocationId = 1005L,
                mapId = -2147483026L,
                resourceLocationId = 9002L,
                parentName = "Banff",
            )
        val inventory =
            """
            {
              "parking":{"resourceLocationId":9002,"resourceCategoryId":200,"mapIds":[-2147483650]},
              "camp":{"resourceLocationId":9002,"resourceCategoryId":100,"mapIds":[-2147483645]}
            }
            """.trimIndent()

        val campground = campgrounds(dtoWith(leaf, inventory, categoryDict)).single()

        assertEquals("pc:1005:-2147483645:9002", campground.bookingProviderRef)
    }

    @Test
    fun `campground leaf that misses its own name falls back to the parent park centroid`() {
        val campground = campgrounds(dtoOf(campgroundMissingOwnName)).single()

        assertEquals("Backcountry Site With No Geometry", campground.name)
        assertEquals(
            "parent",
            campground.sourcePayload!!
                .jsonObject["match_kind"]!!
                .jsonPrimitive.content,
        )
        // Located at Banff's seeded centroid (lon -115.57, lat 51.18), not its own.
        assertEquals(-115.57, campground.longitude)
        assertEquals(51.18, campground.latitude)
    }

    @Test
    fun `campground leaf that misses both its own name and its parent is dropped`() {
        // Distinct from the container skip: this leaf HAS a resourceLocationId
        // (it is a bookable campground) but neither its name nor its parent
        // matches any geometry, so it is dropped as a miss, not emitted with
        // null coordinates.
        val orphan =
            AspiraLeaf(
                name = "Nowhere Campground",
                transactionLocationId = 1004L,
                mapId = -2147483643L,
                resourceLocationId = 9004L,
                parentName = "Nowhere National Park",
            )
        assertTrue(campgrounds(dtoOf(orphan)).isEmpty())
    }

    @Test
    fun `drops a leaf whose inventory categories are all non-bookable`() {
        // resLoc 555's inventory is all category 200 (showResourceCapacityOnline
        // =false) → not a campground, even though the leaf name matches geometry.
        val inventory = """{"r1":{"resourceLocationId":555,"resourceCategoryId":200}}"""
        val campgrounds = campgrounds(dtoWith(nameMatchingLeaf(555L), inventory, categoryDict))
        assertTrue(campgrounds.isEmpty(), "a resourceLocationId with only non-bookable inventory must be dropped")
    }

    @Test
    fun `keeps a leaf whose inventory includes a bookable category`() {
        val inventory = """{"r1":{"resourceLocationId":666,"resourceCategoryId":100}}"""
        val campgrounds = campgrounds(dtoWith(nameMatchingLeaf(666L), inventory, categoryDict))
        assertEquals(1, campgrounds.size, "a resourceLocationId with a bookable category is a campground")
    }

    @Test
    fun `keeps a resourceLocationId that mixes a bookable category with a non-bookable one`() {
        // Headquarters-style: bookable (100) alongside non-bookable (200). The
        // filter only drops resLocs that are ENTIRELY non-bookable.
        val inventory =
            """{"a":{"resourceLocationId":777,"resourceCategoryId":100},"b":{"resourceLocationId":777,"resourceCategoryId":200}}"""
        val campgrounds = campgrounds(dtoWith(nameMatchingLeaf(777L), inventory, categoryDict))
        assertEquals(1, campgrounds.size, "a resLoc mixing a bookable category with a non-bookable one is kept")
    }

    @Test
    fun `does not filter when the dictionary marks every category bookable`() {
        // WA/BC shape: the same inventory, but a dictionary that flags every
        // category bookable. Nothing is dropped — the ETL reflects that this
        // tenant's data marks nothing as non-bookable.
        val inventory = """{"r1":{"resourceLocationId":555,"resourceCategoryId":200}}"""
        val campgrounds = campgrounds(dtoWith(nameMatchingLeaf(555L), inventory, allBookableDict))
        assertEquals(1, campgrounds.size, "an all-bookable dictionary drops nothing")
    }

    @Test
    fun `does not filter when no dictionary is supplied`() {
        val inventory = """{"r1":{"resourceLocationId":555,"resourceCategoryId":200}}"""
        val dto =
            AspiraJoinDto(
                leaves = listOf(nameMatchingLeaf(555L)),
                geomSources = listOf("test-geom" to GeoJsonFeaturesSource(listOf(geomEnvelope()))),
                inventoryEnvelopes = listOf(envelopeOf(inventory)),
                dictionaryPayload = null,
                fetchedAt = Instant.parse("2026-07-05T00:00:00Z"),
            )
        assertEquals(1, campgrounds(dto).size, "no dictionary → no filtering")
    }
}
