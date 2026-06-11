package ca.floo.roadtrip.service.etl

import ca.floo.roadtrip.models.Envelope
import ca.floo.roadtrip.models.RequestMeta
import ca.floo.roadtrip.models.ResponseMeta
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.aspira.AspiraJoinByNameEtl
import ca.floo.roadtrip.service.etl.aspira.AspiraJoinDto
import ca.floo.roadtrip.service.etl.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.aspira.AspiraLeavesPayload
import ca.floo.roadtrip.service.etl.aspira.GeoJsonFeaturesSource
import ca.floo.roadtrip.service.etl.osmpf.OverpassCenter
import ca.floo.roadtrip.service.etl.osmpf.OverpassElement
import ca.floo.roadtrip.service.etl.osmpf.PlanetFitnessEtl
import ca.floo.roadtrip.service.etl.osmpf.PlanetFitnessRawDto
import ca.floo.roadtrip.service.etl.reserveamerica.ParsedPark
import ca.floo.roadtrip.service.etl.reserveamerica.ReserveAmericaDto
import ca.floo.roadtrip.service.etl.reserveamerica.ReserveAmericaEtl
import ca.floo.roadtrip.service.etl.tesla.TeslaIndexDto
import ca.floo.roadtrip.service.etl.tesla.TeslaIndexEtl
import ca.floo.roadtrip.service.etl.tesla.TeslaIndexRow
import ca.floo.roadtrip.service.etl.tesla.TeslaSuperchargerFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EtlExtrasDtoTest {
    @Test
    fun `reserve america extras serialize through dto with sparse optional fields`() {
        val poi =
            ReserveAmericaEtl()
                .transform(
                    ReserveAmericaDto(
                        parks =
                            listOf(
                                ParsedPark(
                                    parkId = 123,
                                    name = "Writing-on-Stone Provincial Park",
                                    lat = 49.083,
                                    lon = -111.617,
                                    phone = null,
                                    photoUrl = "https://example.test/photo.jpg",
                                    infoUrl = "https://example.test/park",
                                ),
                            ),
                        fetchedAt = FETCHED_AT,
                    ),
                    transformCtx(),
                ).single()

        val extras = poi.extras!!.jsonObject
        assertEquals(123, extras["park_id"]!!.jsonPrimitive.int)
        assertEquals("Writing-on-Stone Provincial Park", extras["name"]!!.jsonPrimitive.content)
        assertEquals(49.083, extras["latitude"]!!.jsonPrimitive.double)
        assertEquals(-111.617, extras["longitude"]!!.jsonPrimitive.double)
        assertEquals("https://example.test/photo.jpg", extras["photo_url"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/park", extras["info_url"]!!.jsonPrimitive.content)
        assertNull(extras["phone"])
    }

    @Test
    fun `tesla extras preserve index and explicit null detail payloads`() {
        val rawIndex = Json.parseToJsonElement("""{"location_url_slug":"test-slug","title":"locations"}""").jsonObject
        val poi =
            TeslaIndexEtl()
                .transform(
                    TeslaIndexDto(
                        rows =
                            listOf(
                                TeslaIndexRow(
                                    latitude = 49.0,
                                    longitude = -123.0,
                                    title = "locations",
                                    locationUrlSlug = "test-slug",
                                    superchargerFunction = TeslaSuperchargerFunction(showOnFindUs = "1"),
                                ),
                            ),
                        rawBySlug = mapOf("test-slug" to rawIndex),
                        fetchedAt = FETCHED_AT,
                    ),
                    transformCtx(),
                ).single()

        val extras = poi.extras!!.jsonObject
        assertEquals("test-slug", extras["index"]!!.jsonObject["location_url_slug"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, extras["detail"])
    }

    @Test
    fun `planet fitness extras serialize center and tags through sparse dto`() {
        val poi =
            PlanetFitnessEtl()
                .transform(
                    PlanetFitnessRawDto(
                        elements =
                            listOf(
                                OverpassElement(
                                    type = "way",
                                    id = 456,
                                    center = OverpassCenter(lat = 47.61, lon = -122.33),
                                    tags =
                                        mapOf(
                                            "name" to "Planet Fitness",
                                            "opening_hours" to "Mo-Fr 05:00-22:00",
                                        ),
                                ),
                            ),
                        _fetchedAt = FETCHED_AT,
                    ),
                    transformCtx(),
                ).single()

        val extras = poi.extras!!.jsonObject
        assertEquals("way", extras["type"]!!.jsonPrimitive.content)
        assertEquals(456, extras["id"]!!.jsonPrimitive.int)
        assertEquals(47.61, extras["center"]!!.jsonObject["lat"]!!.jsonPrimitive.double)
        assertEquals(-122.33, extras["center"]!!.jsonObject["lon"]!!.jsonPrimitive.double)
        assertEquals("Mo-Fr 05:00-22:00", extras["tags"]!!.jsonObject["opening_hours"]!!.jsonPrimitive.content)
        assertNull(extras["lat"])
        assertNull(extras["lon"])
    }

    @Test
    fun `aspira extras preserve explicit null ids and names`() {
        val poi =
            AspiraJoinByNameEtl("aspira-bc-pins")
                .transform(
                    AspiraJoinDto(
                        leaves =
                            AspiraLeavesPayload(
                                slug = "aspira-leaves-bc",
                                leaves =
                                    listOf(
                                        AspiraLeaf(
                                            name = "Lakeside Campground",
                                            transactionLocationId = 11,
                                            mapId = 22,
                                            resourceLocationId = null,
                                            parentName = null,
                                        ),
                                    ),
                            ),
                        geomSources = listOf("fixture" to GeoJsonFeaturesSource(listOf(geoJsonEnvelope()), "fixture")),
                        fetchedAt = FETCHED_AT,
                    ),
                    transformCtx(),
                ).single()

        val extras = poi.extras!!.jsonObject
        assertEquals("camping.bcparks.ca", extras["host"]!!.jsonPrimitive.content)
        assertEquals(11, extras["transaction_location_id"]!!.jsonPrimitive.int)
        assertEquals(22, extras["map_id"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, extras["resource_location_id"])
        assertEquals(JsonNull, extras["parent_name"])
        assertEquals("exact", extras["match_kind"]!!.jsonPrimitive.content)
    }

    private fun geoJsonEnvelope(): Envelope =
        Envelope(
            fetcher = "fixture",
            fetcherVersion = "1",
            fetchedAt = FETCHED_AT.toString(),
            request = RequestMeta(url = "https://example.test", method = "GET"),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    {
                      "type": "FeatureCollection",
                      "features": [
                        {
                          "type": "Feature",
                          "properties": { "name": "Lakeside Campground" },
                          "geometry": { "type": "Point", "coordinates": [-123.1, 49.3] }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

    private fun transformCtx(): TransformCtx {
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        return TransformCtx.load(File("build/tmp/etl-extras-dto-test-raw"), PoiRegistry.load(yamlPath))
    }

    private companion object {
        val FETCHED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
