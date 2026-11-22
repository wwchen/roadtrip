package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.model.domain.CatalogPhoto
import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.terminalOkRecords
import ca.floo.roadtrip.service.etl.framework.terminalRecords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CampflareCampsitesEtlTest {
    @Test
    fun `transforms campflare campsite dump rows into canonical records`() {
        val etl = CampflareCampsitesEtl()
        val rows = terminalRecords(etl, bundle(campsitePayload()), transformCtx())
        val row = rows.single()

        assertEquals(DataProvider.CAMPFLARE, row.dataProviderRef.provider)
        assertEquals("upper-pines-site-001", row.dataProviderRef.serialize())
        val parentDataProviderRef = assertNotNull(row.parentDataProviderRef)
        assertEquals(DataProvider.CAMPFLARE, parentDataProviderRef.provider)
        assertEquals("upper-pines-campground-447", parentDataProviderRef.serialize())
        assertEquals("Site 001", row.name)
        assertEquals(CampsiteKind.TENT, row.kind)
        assertEquals("Tent Site", row.kindListed)
        assertEquals("A", row.loopName)
        assertEquals(37.738, row.latitude)
        assertEquals(-119.566, row.longitude)
        val sourceName =
            row.sourcePayload!!
                .jsonObject["name"]!!
                .jsonPrimitive
                .content
        assertEquals(listOf("Tent"), row.equipment)
        assertEquals(listOf(CatalogPhoto("https://cdn.example/site.jpg")), row.photos)
        assertEquals(6, row.maxPeople)
        assertEquals("Quiet site", row.description)
        assertEquals("Site 001", sourceName)
        assertEquals(BookingProvider.CAMPFLARE, row.bookingProvider)
        assertEquals("upper-pines-site-001", row.bookingProviderRef)
        assertEquals(listOf(BookingAlias(BookingProvider.RECGOV, "001")), row.bookingAliases)
    }

    @Test
    fun `a campsite with no rec_gov reservation url carries no alias`() {
        val etl = CampflareCampsitesEtl()
        val rows =
            terminalRecords(
                etl,
                bundle(
                    """
                    [
                      {
                        "id":"cranberry-lake-site-4",
                        "campground_id":"cranberry-lake-wsp",
                        "name":"Site 4",
                        "kind":"standard",
                        "reservation_url":"https://washington.goingtocamp.com/"
                      }
                    ]
                    """.trimIndent(),
                ),
                transformCtx(),
            )

        val row = rows.single()
        assertEquals(BookingProvider.CAMPFLARE, row.bookingProvider)
        assertEquals("cranberry-lake-site-4", row.bookingProviderRef)
        assertEquals(emptyList(), row.bookingAliases)
    }

    @Test
    fun `skips campsite rows without id parent or name and defaults missing kind`() {
        val etl = CampflareCampsitesEtl()
        val rows =
            terminalOkRecords(
                etl,
                bundle(
                    """
                    [
                      {"id":"missing-parent","name":"No Parent","kind":"standard"},
                      {"id":"missing-name","campground_id":"cg","kind":"standard"},
                      {"id":"missing-kind","campground_id":"cg","name":"No Kind"},
                      {"id":"ok","campground_id":"cg","name":"Valid","kind":"standard"}
                    ]
                    """.trimIndent(),
                ),
                transformCtx(),
            )

        assertEquals(listOf("missing-kind", "ok"), rows.map { it.dataProviderRef.serialize() })
        val missingKind = rows.single { it.dataProviderRef.serialize() == "missing-kind" }
        assertEquals(CampsiteKind.OTHER, missingKind.kind)
        assertNull(missingKind.kindListed)
    }

    @Test
    fun `normalizes E6 campsite coordinates when dump mixes coordinate scales`() {
        val etl = CampflareCampsitesEtl()
        val rows =
            terminalRecords(
                etl,
                bundle(
                    """
                    [
                      {
                        "id":"scaled-lon",
                        "campground_id":"cg",
                        "name":"Cabin 5",
                        "kind":"cabin",
                        "latitude":29.740556,
                        "longitude":-91853611
                      },
                      {
                        "id":"scaled-lat",
                        "campground_id":"cg",
                        "name":"Site 25",
                        "kind":"standard",
                        "latitude":31957925,
                        "longitude":-91.20201
                      }
                    ]
                    """.trimIndent(),
                ),
                transformCtx(),
            )

        assertEquals(-91.853611, rows.single { it.dataProviderRef.serialize() == "scaled-lon" }.longitude)
        assertEquals(31.957925, rows.single { it.dataProviderRef.serialize() == "scaled-lat" }.latitude)
    }

    @Test
    fun `keeps the copy under the campground its loop name names`() {
        val sites =
            """
            [
              {"id":"a","campground_id":"shad","name":"A","loop_name":"Milburn Landing Nanticoke Loop"},
              {"id":"b","campground_id":"shad","name":"B","loop_name":"Shad Landing Fox Den Loop"},
              {"id":"a","campground_id":"milburn","name":"A","loop_name":"Milburn Landing Nanticoke Loop"},
              {"id":"b","campground_id":"milburn","name":"B","loop_name":"Shad Landing Fox Den Loop"}
            ]
            """.trimIndent()
        assertEquals(listOf("b@shad", "a@milburn"), homesOf(sites, parkCampgrounds))
    }

    @Test
    fun `a loop that names no campground goes to the one named only for the park`() {
        val sites =
            """
            [
              {"id":"c","campground_id":"manor","name":"C","loop_name":"Bear Branch Loop"},
              {"id":"d","campground_id":"manor","name":"D","loop_name":"Manor Camping Area"},
              {"id":"c","campground_id":"falls","name":"C","loop_name":"Bear Branch Loop"},
              {"id":"d","campground_id":"falls","name":"D","loop_name":"Manor Camping Area"}
            ]
            """.trimIndent()
        assertEquals(listOf("d@manor", "c@falls"), homesOf(sites, parkCampgrounds))
    }

    @Test
    fun `keeps the first-listed copy when neither rule decides`() {
        val sites =
            """
            [
              {"id":"e","campground_id":"hollofield","name":"E","loop_name":"McKeldin Area"},
              {"id":"e","campground_id":"hilton","name":"E","loop_name":"McKeldin Area"}
            ]
            """.trimIndent()
        assertEquals(listOf("e@hollofield"), homesOf(sites, parkCampgrounds))
        assertEquals(listOf("e@hollofield"), homesOf(sites, null))
    }

    @Test
    fun `repeats under one campground are left for the dedupe`() {
        val sites = """[{"id":"f","campground_id":"shad","name":"F"},{"id":"f","campground_id":"shad","name":"F"}]"""
        assertEquals(listOf("f@shad", "f@shad"), homesOf(sites, parkCampgrounds))
    }

    private fun homesOf(
        sitesJson: String,
        campgroundsJson: String?,
    ): List<String> {
        val captures = linkedMapOf("campflare-campsites-export" to listOf(envelope(sitesJson)))
        if (campgroundsJson != null) captures["campflare-campgrounds-export"] = listOf(envelope(campgroundsJson))
        return CampflareCampsitesEtl()
            .parse(InputBundle(rawCaptures = captures))
            .map { parsed ->
                val dto = (parsed as ParseResult.Ok).dto
                "${dto["id"]!!.jsonPrimitive.content}@${dto["campground_id"]!!.jsonPrimitive.content}"
            }.toList()
    }

    private fun bundle(payloadJson: String): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("campflare-campsites-export" to listOf(envelope(payloadJson))),
        )

    private fun envelope(payloadJson: String): Envelope =
        Envelope(
            fetcher = "fetch_campflare_dump",
            fetcherVersion = "1",
            fetchedAt = "2026-07-08T00:00:00Z",
            request = RequestMeta(url = "https://api.campflare.com/dumps/latest/campsites", method = "GET"),
            response = ResponseMeta(status = 200),
            payload = Json.parseToJsonElement(payloadJson),
            part = "part-000001",
        )

    private fun transformCtx(): TransformCtx =
        TransformCtx.load(
            rawDir = File("build/tmp/cf-campsites-etl-test-raw"),
            registry = PoiRegistry(dataSources = emptyList(), poiData = emptyList()),
        )

    private fun campsitePayload(): String =
        """
        [
          {
            "id": "upper-pines-site-001",
            "campground_id": "upper-pines-campground-447",
            "name": "Site 001",
            "kind": "tent-only",
            "loop_name": "A",
            "latitude": 37.738,
            "longitude": -119.566,
            "reservation_url": "https://www.recreation.gov/camping/campsites/001",
            "equipment": [{"name": "Tent"}],
            "kind_listed": "Tent Site",
            "description": "<b>Quiet</b> site",
            "schedule": {"check_in_time": "14:00", "uniform": true},
            "price": {"per_night": 36, "currency_code": "USD"},
            "firepit": true,
            "picnic_table": true,
            "ada_accessible": false,
            "water_hookups": false,
            "electric_hookups": false,
            "sewer_hookups": false,
            "max_people": 6,
            "max_cars": 2,
            "pull_through": false,
            "driveway_length": 24,
            "max_rv_length": 20,
            "max_trailer_length": 18.5,
            "photos": [{"original_url": "https://cdn.example/site.jpg"}]
          }
        ]
        """.trimIndent()

    private companion object {
        val parkCampgrounds =
            """
            [
              {"id":"shad","name":"Shad Landing Pocomoke River State Park Campground"},
              {"id":"milburn","name":"Milburn Landing Campground"},
              {"id":"manor","name":"Manor Cunningham Falls State Park Campground"},
              {"id":"falls","name":"Cunningham Falls State Park Campground"},
              {"id":"hollofield","name":"Patapsco Valley State Park Hollofield Campground"},
              {"id":"hilton","name":"Patapsco Valley Hilton Vally State Park Campground"}
            ]
            """.trimIndent()
    }
}
