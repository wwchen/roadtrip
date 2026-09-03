package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import ca.floo.roadtrip.service.etl.framework.terminalOkRecords
import ca.floo.roadtrip.service.etl.framework.terminalRecords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        assertEquals("tent-only", row.kind)
        assertEquals("A", row.loopName)
        assertEquals(37.738, row.latitude)
        assertEquals(-119.566, row.longitude)
        val equipmentName =
            row.equipment!!
                .jsonArray
                .single()
                .jsonObject["name"]!!
                .jsonPrimitive
                .content
        val sourceName =
            row.sourcePayload!!
                .jsonObject["name"]!!
                .jsonPrimitive
                .content
        assertEquals("Tent", equipmentName)
        assertEquals(6, row.maxPeople)
        assertEquals("""[{"url":"https://cdn.example/site.jpg"}]""", row.photos.toString())
        assertEquals("Site 001", sourceName)
        assertEquals(BookingProvider.RECGOV, row.bookingProvider)
        assertEquals("001", row.bookingProviderRef)
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
        assertEquals("site", rows.single { it.dataProviderRef.serialize() == "missing-kind" }.kind)
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

    private fun bundle(payloadJson: String): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("campflare-campsites" to listOf(envelope(payloadJson))),
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
}
