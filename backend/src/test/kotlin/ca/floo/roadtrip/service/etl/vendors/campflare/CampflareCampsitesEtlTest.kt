package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

class CampflareCampsitesEtlTest {
    @Test
    fun `transforms campflare campsite dump rows into canonical records`() {
        val etl = CampflareCampsitesEtl()
        val out = etl.transform(etl.parse(bundle(campsitePayload())), transformCtx())
        val row = out.campsites.single()

        assertEquals("campflare", row.vendor)
        assertEquals("upper-pines-site-001", row.vendorRefId)
        assertEquals("campflare", row.parentVendor)
        assertEquals("upper-pines-campground-447", row.parentVendorRefId)
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
        val parentCampgroundId =
            row.vendorRefPayload!!
                .jsonObject["campground_id"]!!
                .jsonPrimitive
                .content
        val sourceName =
            row.sourcePayload!!
                .jsonObject["name"]!!
                .jsonPrimitive
                .content
        val recgovRef = row.additionalVendorRefs.single()
        assertEquals("Tent", equipmentName)
        assertEquals(6, row.maxPeople)
        assertEquals("upper-pines-campground-447", parentCampgroundId)
        assertEquals("Site 001", sourceName)
        assertEquals("recgov", recgovRef.vendor)
        assertEquals("001", recgovRef.vendorRefId)
        assertEquals(
            "001",
            recgovRef.payload!!
                .jsonObject["recgov_id"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `skips campsite rows without id parent or name and defaults missing kind`() {
        val etl = CampflareCampsitesEtl()
        val out =
            etl.transform(
                etl.parse(
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
                ),
                transformCtx(),
            )

        assertEquals(listOf("missing-kind", "ok"), out.campsites.map { it.vendorRefId })
        assertEquals("site", out.campsites.single { it.vendorRefId == "missing-kind" }.kind)
    }

    @Test
    fun `normalizes E6 campsite coordinates when dump mixes coordinate scales`() {
        val etl = CampflareCampsitesEtl()
        val out =
            etl.transform(
                etl.parse(
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
                ),
                transformCtx(),
            )

        assertEquals(-91.853611, out.campsites.single { it.vendorRefId == "scaled-lon" }.longitude)
        assertEquals(31.957925, out.campsites.single { it.vendorRefId == "scaled-lat" }.latitude)
    }

    private fun bundle(payloadJson: String): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("campflare-campsites" to listOf(envelope(payloadJson))),
            etlOutputs = linkedMapOf(),
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
