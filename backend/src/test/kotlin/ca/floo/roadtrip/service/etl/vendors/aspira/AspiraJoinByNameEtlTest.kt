package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit test for the campground-level POI emission in [AspiraJoinByNameEtl].
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
class AspiraJoinByNameEtlTest {
    private lateinit var ctx: TransformCtx

    // Real terminal slug so args (host), subcategory (federal) and the
    // constant agency (Parks Canada) resolve from the production YAML.
    private val slug = "aspira-pc-pins"

    @BeforeAll
    fun setUp() {
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        val registry = PoiRegistry.load(yamlPath)
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
            leaves = AspiraLeavesPayload(slug = slug, leaves = leaves.toList()),
            geomSources =
                listOf(
                    "test-geom" to GeoJsonFeaturesSource(listOf(geomEnvelope()), "test-geom"),
                ),
            fetchedAt = Instant.parse("2026-07-05T00:00:00Z"),
        )

    /** DTO variant carrying an inventory envelope + category dictionary for the non-bookable filter. */
    private fun dtoWith(
        leaf: AspiraLeaf,
        inventoryPayloadJson: String,
        dictionaryPayloadJson: String,
        etlSlug: String = slug,
    ): AspiraJoinDto =
        AspiraJoinDto(
            leaves = AspiraLeavesPayload(slug = etlSlug, leaves = listOf(leaf)),
            geomSources =
                listOf(
                    "test-geom" to GeoJsonFeaturesSource(listOf(geomEnvelope()), "test-geom"),
                ),
            inventoryEnvelopes = listOf(envelopeOf(inventoryPayloadJson)),
            dictionaryPayload = Json.parseToJsonElement(dictionaryPayloadJson).jsonObject,
            fetchedAt = Instant.parse("2026-07-05T00:00:00Z"),
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

    @Test
    fun `drops park-container leaves even when their name matches geometry`() {
        val pois = AspiraJoinByNameEtl(slug).transform(dtoOf(parkContainer, campground), ctx)

        assertEquals(1, pois.size, "only the campground node should become a POI")
        val poi = pois.single()
        assertEquals("Two Jack Lakeside", poi.name)
        val ref = poi.providerRef as ProviderRef.Aspira
        assertEquals(9002L, ref.resourceLocationId)
    }

    @Test
    fun `emits nothing when every leaf is a park container`() {
        val pois = AspiraJoinByNameEtl(slug).transform(dtoOf(parkContainer), ctx)
        assertTrue(pois.isEmpty(), "a park with no campground children yields no POI")
    }

    @Test
    fun `keeps campground POIs keyed by transactionLocationId and mapId`() {
        val poi = AspiraJoinByNameEtl(slug).transform(dtoOf(campground), ctx).single()

        // Joiner rule (A) matches pois.source_id = "aspira-{txn}-{map}".
        assertEquals("aspira-1002--2147483641", poi.sourceId)
        assertEquals("federal", poi.subcategory)
        assertEquals("Parks Canada", poi.agency)
    }

    @Test
    fun `resourceLocationId is carried into provider_ref for the joiner`() {
        val poi = AspiraJoinByNameEtl(slug).transform(dtoOf(campground), ctx).single()

        val extras = poi.extras!!.jsonObject
        assertEquals("9002", extras["resource_location_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `extras record match provenance and host`() {
        val poi = AspiraJoinByNameEtl(slug).transform(dtoOf(campground), ctx).single()
        val extras = poi.extras!!.jsonObject
        assertEquals("reservation.pc.gc.ca", extras["host"]!!.jsonPrimitive.content)
        assertEquals("exact", extras["match_kind"]!!.jsonPrimitive.content)
    }

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

    @Test
    fun `campground leaf that misses its own name falls back to the parent park centroid`() {
        val poi = AspiraJoinByNameEtl(slug).transform(dtoOf(campgroundMissingOwnName), ctx).single()

        assertEquals("Backcountry Site With No Geometry", poi.name)
        assertEquals(
            "parent",
            poi.extras!!
                .jsonObject["match_kind"]!!
                .jsonPrimitive.content,
        )
        // Located at Banff's seeded centroid (lon -115.57, lat 51.18), not its own.
        assertTrue(
            poi.geomGeoJson.contains("-115.57") && poi.geomGeoJson.contains("51.18"),
            "expected parent-park centroid coordinates, got ${poi.geomGeoJson}",
        )
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
        assertTrue(AspiraJoinByNameEtl(slug).transform(dtoOf(orphan), ctx).isEmpty())
    }

    @Test
    fun `drops a leaf whose inventory categories are all non-bookable`() {
        // resLoc 555's inventory is all category 200 (showResourceCapacityOnline
        // =false) → not a campground, even though the leaf name matches geometry.
        val inventory = """{"r1":{"resourceLocationId":555,"resourceCategoryId":200}}"""
        val pois = AspiraJoinByNameEtl(slug).transform(dtoWith(nameMatchingLeaf(555L), inventory, categoryDict), ctx)
        assertTrue(pois.isEmpty(), "a resourceLocationId with only non-bookable inventory must be dropped")
    }

    @Test
    fun `keeps a leaf whose inventory includes a bookable category`() {
        val inventory = """{"r1":{"resourceLocationId":666,"resourceCategoryId":100}}"""
        val pois = AspiraJoinByNameEtl(slug).transform(dtoWith(nameMatchingLeaf(666L), inventory, categoryDict), ctx)
        assertEquals(1, pois.size, "a resourceLocationId with a bookable category is a campground")
    }

    @Test
    fun `keeps a resourceLocationId that mixes a bookable category with a non-bookable one`() {
        // Headquarters-style: bookable (100) alongside non-bookable (200). The
        // filter only drops resLocs that are ENTIRELY non-bookable.
        val inventory =
            """{"a":{"resourceLocationId":777,"resourceCategoryId":100},"b":{"resourceLocationId":777,"resourceCategoryId":200}}"""
        val pois = AspiraJoinByNameEtl(slug).transform(dtoWith(nameMatchingLeaf(777L), inventory, categoryDict), ctx)
        assertEquals(1, pois.size, "a resLoc mixing a bookable category with a non-bookable one is kept")
    }

    @Test
    fun `does not filter when the dictionary marks every category bookable`() {
        // WA/BC shape: the same inventory, but a dictionary that flags every
        // category bookable. Nothing is dropped — the ETL reflects that this
        // tenant's data marks nothing as non-bookable.
        val inventory = """{"r1":{"resourceLocationId":555,"resourceCategoryId":200}}"""
        val pois = AspiraJoinByNameEtl(slug).transform(dtoWith(nameMatchingLeaf(555L), inventory, allBookableDict), ctx)
        assertEquals(1, pois.size, "an all-bookable dictionary drops nothing")
    }

    @Test
    fun `does not filter when no dictionary is supplied`() {
        val inventory = """{"r1":{"resourceLocationId":555,"resourceCategoryId":200}}"""
        val dto =
            AspiraJoinDto(
                leaves = AspiraLeavesPayload(slug = slug, leaves = listOf(nameMatchingLeaf(555L))),
                geomSources = listOf("test-geom" to GeoJsonFeaturesSource(listOf(geomEnvelope()), "test-geom")),
                inventoryEnvelopes = listOf(envelopeOf(inventory)),
                dictionaryPayload = null,
                fetchedAt = Instant.parse("2026-07-05T00:00:00Z"),
            )
        assertEquals(1, AspiraJoinByNameEtl(slug).transform(dto, ctx).size, "no dictionary → no filtering")
    }
}
