package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraJoinByNameEtl
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraJoinDto
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeaf
import ca.floo.roadtrip.service.etl.vendors.aspira.AspiraLeavesPayload
import ca.floo.roadtrip.service.etl.vendors.aspira.GeoJsonFeaturesSource
import ca.floo.roadtrip.service.etl.vendors.osmpf.OverpassCenter
import ca.floo.roadtrip.service.etl.vendors.osmpf.OverpassElement
import ca.floo.roadtrip.service.etl.vendors.osmpf.PlanetFitnessEtl
import ca.floo.roadtrip.service.etl.vendors.osmpf.PlanetFitnessRawDto
import ca.floo.roadtrip.service.etl.vendors.reserveamerica.ParsedPark
import ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaDto
import ca.floo.roadtrip.service.etl.vendors.reserveamerica.ReserveAmericaEtl
import ca.floo.roadtrip.service.etl.vendors.tesla.TeslaIndexDto
import ca.floo.roadtrip.service.etl.vendors.tesla.TeslaIndexEtl
import ca.floo.roadtrip.service.etl.vendors.tesla.TeslaIndexRow
import ca.floo.roadtrip.service.etl.vendors.tesla.TeslaSuperchargerFunction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EtlExtrasDtoTest {
    @Test
    fun `reserve america extras serialize through dto with sparse optional fields`() {
        val campground =
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
                                    description = "Hoodoo country camping.",
                                    photoUrl = "https://example.test/photo.jpg",
                                    infoUrl = "https://example.test/park",
                                ),
                            ),
                        fetchedAt = fetchedAt,
                    ),
                    transformCtx(),
                ).campgrounds
                .single()

        val extras = campground.metadata!!.jsonObject
        assertEquals(123, extras["park_id"]!!.jsonPrimitive.int)
        assertEquals("Writing-on-Stone Provincial Park", extras["name"]!!.jsonPrimitive.content)
        assertEquals(49.083, extras["latitude"]!!.jsonPrimitive.double)
        assertEquals(-111.617, extras["longitude"]!!.jsonPrimitive.double)
        assertEquals("Hoodoo country camping.", extras["description"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/photo.jpg", extras["photo_url"]!!.jsonPrimitive.content)
        assertEquals("https://example.test/park", extras["info_url"]!!.jsonPrimitive.content)
        assertNull(extras["phone"])
    }

    @Test
    fun `tesla canonical output preserves index and nullable detail payloads`() {
        val rawIndex = Json.parseToJsonElement("""{"location_url_slug":"test-slug","title":"locations"}""").jsonObject
        val record =
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
                        fetchedAt = fetchedAt,
                    ),
                    transformCtx(),
                ).superchargers
                .single()

        assertEquals("test-slug", record.locationSlug)
        assertEquals(
            "test-slug",
            record
                .indexPayload!!
                .jsonObject["location_url_slug"]!!
                .jsonPrimitive
                .content,
        )
        assertNull(record.detailPayload)
    }

    @Test
    fun `tesla canonical output treats explicit null amenities as missing`() {
        val rawDir = Files.createTempDirectory("tesla-null-amenities-etl-test").toFile()
        val slug = "test-slug"
        val slugDir = rawDir.resolve("tesla-locations").resolve(slug)
        slugDir.mkdirs()
        slugDir.resolve("2026-01-01T00-00-00Z.json").writeText(
            Json.encodeToString(
                Envelope.serializer(),
                Envelope(
                    fetcher = "fixture",
                    fetcherVersion = "1",
                    fetchedAt = fetchedAt.toString(),
                    request = RequestMeta(url = "https://example.test/tesla/$slug", method = "GET"),
                    response = ResponseMeta(status = 200),
                    payload =
                        Json.parseToJsonElement(
                            """
                            {
                              "data": {
                                "data": {
                                  "name": "Test Supercharger",
                                  "amenities": null,
                                  "availabilityProfile": null
                                }
                              }
                            }
                            """.trimIndent(),
                        ),
                ),
            ),
        )
        val rawIndex = Json.parseToJsonElement("""{"location_url_slug":"$slug","title":"locations"}""").jsonObject
        val record =
            TeslaIndexEtl()
                .transform(
                    TeslaIndexDto(
                        rows =
                            listOf(
                                TeslaIndexRow(
                                    latitude = 49.0,
                                    longitude = -123.0,
                                    title = "locations",
                                    locationUrlSlug = slug,
                                    superchargerFunction = TeslaSuperchargerFunction(showOnFindUs = "1"),
                                ),
                            ),
                        rawBySlug = mapOf(slug to rawIndex),
                        fetchedAt = fetchedAt,
                    ),
                    transformCtx(rawDir),
                ).superchargers
                .single()

        assertNull(record.amenities)
        assertNull(record.availabilityProfile)
    }

    @Test
    fun `tesla canonical output promotes enriched detail fields`() {
        val rawDir = Files.createTempDirectory("tesla-detail-etl-test").toFile()
        val slug = "test-slug"
        val slugDir = rawDir.resolve("tesla-locations").resolve(slug)
        slugDir.mkdirs()
        slugDir.resolve("2026-01-01T00-00-00Z.json").writeText(
            Json.encodeToString(
                Envelope.serializer(),
                Envelope(
                    fetcher = "fixture",
                    fetcherVersion = "1",
                    fetchedAt = fetchedAt.toString(),
                    request = RequestMeta(url = "https://example.test/tesla/$slug", method = "GET"),
                    response = ResponseMeta(status = 200),
                    payload =
                        Json.parseToJsonElement(
                            """
                            {
                              "data": {
                                "data": {
                                  "name": "Test Supercharger",
                                  "commonSiteName": "Lot B",
                                  "locationGUID": "guid-123",
                                  "timeZone": "America/Vancouver",
                                  "openToPublic": true,
                                  "openToNonTeslas": false,
                                  "isTrailerFriendly": true,
                                  "accessHours": { "twentyFourSeven": true },
                                  "publicStallCount": 12,
                                  "maxPowerKw": 250,
                                  "address": {
                                    "streetNumber": "100",
                                    "street": "Main St",
                                    "city": "Vancouver",
                                    "state": "BC",
                                    "postalCode": "V6B 1A1",
                                    "countryCode": "CA"
                                  },
                                  "amenities": ["AMENITIES_RESTROOMS"],
                                  "availabilityProfile": {
                                    "availabilityProfile": {
                                      "sunday": { "congestionValue": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0] }
                                    }
                                  },
                                  "effectivePricebooks": [
                                    {
                                      "feeType": "CHARGING",
                                      "rateBase": 0.42,
                                      "currencyCode": "CAD",
                                      "uom": "kwh",
                                      "isTou": false,
                                      "vehicleMakeType": "TSLA"
                                    }
                                  ]
                                }
                              }
                            }
                            """.trimIndent(),
                        ),
                ),
            ),
        )

        val rawIndex = Json.parseToJsonElement("""{"location_url_slug":"$slug","title":"locations"}""").jsonObject
        val record =
            TeslaIndexEtl()
                .transform(
                    TeslaIndexDto(
                        rows =
                            listOf(
                                TeslaIndexRow(
                                    latitude = 49.0,
                                    longitude = -123.0,
                                    title = "locations",
                                    locationUrlSlug = slug,
                                    superchargerFunction = TeslaSuperchargerFunction(showOnFindUs = "1"),
                                ),
                            ),
                        rawBySlug = mapOf(slug to rawIndex),
                        fetchedAt = fetchedAt,
                    ),
                    transformCtx(rawDir),
                ).superchargers
                .single()

        assertEquals("guid-123", record.locationGuid)
        assertEquals("Test Supercharger", record.commonSiteName)
        assertEquals("America/Vancouver", record.timeZone)
        assertEquals(true, record.openToPublic)
        assertEquals(false, record.openToNonTeslas)
        assertEquals(true, record.trailerFriendly)
        assertEquals(true, record.twentyFourSeven)
        assertEquals(
            "AMENITIES_RESTROOMS",
            record
                .amenities!!
                .jsonArray
                .single()
                .jsonPrimitive
                .content,
        )
        assertEquals(
            24,
            record
                .availabilityProfile!!
                .jsonObject["availabilityProfile"]!!
                .jsonObject["sunday"]!!
                .jsonObject["congestionValue"]!!
                .jsonArray
                .size,
        )
        assertEquals(
            "CAD",
            record
                .pricebooks!!
                .jsonArray
                .single()
                .jsonObject["currencyCode"]!!
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "guid-123",
            record
                .detailPayload!!
                .jsonObject["locationGUID"]!!
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `planet fitness extras serialize center and tags through sparse dto`() {
        val record =
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
                        fetchedAt = fetchedAt,
                    ),
                    transformCtx(),
                ).locations
                .single()

        val extras = record.payload!!.jsonObject
        assertEquals("way", extras["type"]!!.jsonPrimitive.content)
        assertEquals(456, extras["id"]!!.jsonPrimitive.int)
        assertEquals(47.61, extras["center"]!!.jsonObject["lat"]!!.jsonPrimitive.double)
        assertEquals(-122.33, extras["center"]!!.jsonObject["lon"]!!.jsonPrimitive.double)
        assertEquals("Mo-Fr 05:00-22:00", extras["tags"]!!.jsonObject["opening_hours"]!!.jsonPrimitive.content)
        assertNull(extras["lat"])
        assertNull(extras["lon"])
    }

    @Test
    fun `aspira extras preserve explicit null parent name`() {
        // An emitted aspira POI always carries a non-null resourceLocationId
        // (leaves without one are park containers, dropped by
        // AspiraJoinByNameEtl before emission). parent_name stays nullable, so
        // it is the field that exercises explicitNulls serialization here.
        val campground =
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
                                            resourceLocationId = 33,
                                            parentName = null,
                                        ),
                                    ),
                            ),
                        geomSources = listOf("fixture" to GeoJsonFeaturesSource(listOf(geoJsonEnvelope()), "fixture")),
                        fetchedAt = fetchedAt,
                    ),
                    transformCtx(),
                ).campgrounds
                .single()

        val extras = campground.metadata!!.jsonObject
        assertEquals("camping.bcparks.ca", extras["host"]!!.jsonPrimitive.content)
        assertEquals(11, extras["transaction_location_id"]!!.jsonPrimitive.int)
        assertEquals(22, extras["map_id"]!!.jsonPrimitive.int)
        assertEquals(33, extras["resource_location_id"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, extras["parent_name"])
        assertEquals("exact", extras["match_kind"]!!.jsonPrimitive.content)
    }

    private fun geoJsonEnvelope(): Envelope =
        Envelope(
            fetcher = "fixture",
            fetcherVersion = "1",
            fetchedAt = fetchedAt.toString(),
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

    private fun transformCtx(rawDir: File = File("build/tmp/etl-extras-dto-test-raw")): TransformCtx =
        TransformCtx.load(rawDir, PoiRegistry.loadResource("poi-registry.yaml"))

    private companion object {
        val fetchedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
