package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProvider
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
import kotlin.test.assertNull

class CampflareCampgroundsEtlTest {
    @Test
    fun `transforms campflare campground dump rows into canonical records`() {
        val etl = CampflareCampgroundsEtl()
        val out = etl.transform(etl.parse(bundle("campflare-campgrounds", campgroundPayload())), transformCtx())
        val row = out.campgrounds.single()

        assertEquals(DataProvider.CAMPFLARE, row.dataProviderRef.provider)
        assertEquals("upper-pines-campground-447", row.dataProviderRef.serialize())
        assertEquals("Upper Pines", row.name)
        assertEquals("open", row.status)
        assertNull(row.statusDescription)
        assertNull(row.shortDescription)
        assertNull(row.mediumDescription)
        assertNull(row.longDescription)
        assertEquals("established", row.kind)
        assertEquals(37.739, row.latitude)
        assertEquals(-119.565, row.longitude)
        assertEquals("https://campflare.com/campground/upper-pines-campground-447", row.sourceUrl)
        val ridbFacilityId =
            row.connections!!
                .jsonObject["ridb_facility_id"]!!
                .jsonPrimitive
                .content
        val hasToilets =
            row.amenities!!
                .jsonObject["toilets"]!!
                .jsonPrimitive
                .content
                .toBooleanStrict()
        val sourceName =
            row.sourcePayload!!
                .jsonObject["name"]!!
                .jsonPrimitive
                .content
        val campflareLink =
            row.links!!
                .jsonArray
                .last()
                .jsonObject
        assertEquals("232447", ridbFacilityId)
        assertEquals(true, hasToilets)
        assertEquals(" Upper Pines ", sourceName)
        assertEquals("Campflare source", campflareLink["title"]!!.jsonPrimitive.content)
        assertEquals(
            "https://campflare.com/campground/upper-pines-campground-447",
            campflareLink["url"]!!.jsonPrimitive.content,
        )
        assertEquals(BookingProvider.RECGOV, row.bookingProvider)
        assertEquals("232447", row.bookingProviderRef)
    }

    @Test
    fun `skips campground rows without id name or coordinates`() {
        val etl = CampflareCampgroundsEtl()
        val out =
            etl.transform(
                etl.parse(
                    bundle(
                        "campflare-campgrounds",
                        """
                        [
                          {"id":"missing-name","location":{"latitude":1,"longitude":2}},
                          {"id":"missing-lat","name":"No Lat","location":{"longitude":2}},
                          {"id":"ok","name":"Valid","location":{"latitude":1,"longitude":2}}
                        ]
                        """.trimIndent(),
                    ),
                ),
                transformCtx(),
            )

        assertEquals(listOf("ok"), out.campgrounds.map { it.dataProviderRef.serialize() })
    }

    @Test
    fun `adds campflare source link when campground dump has no links`() {
        val etl = CampflareCampgroundsEtl()
        val out =
            etl.transform(
                etl.parse(
                    bundle(
                        "campflare-campgrounds",
                        """
                        [
                          {"id":"no-links","name":"No Links","location":{"latitude":1,"longitude":2}}
                        ]
                        """.trimIndent(),
                    ),
                ),
                transformCtx(),
            )

        val link =
            out.campgrounds
                .single()
                .links!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals("Campflare source", link["title"]!!.jsonPrimitive.content)
        assertEquals(
            "https://campflare.com/campground/no-links",
            link["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `does not duplicate existing campflare source href`() {
        val etl = CampflareCampgroundsEtl()
        val out =
            etl.transform(
                etl.parse(
                    bundle(
                        "campflare-campgrounds",
                        """
                        [
                          {
                            "id":"has-campflare-href",
                            "name":"Has Campflare Href",
                            "location":{"latitude":1,"longitude":2},
                            "links":[
                              {
                                "title":"Existing Campflare",
                                "href":"https://campflare.com/campground/has-campflare-href"
                              }
                            ]
                          }
                        ]
                        """.trimIndent(),
                    ),
                ),
                transformCtx(),
            )

        assertEquals(
            1,
            out.campgrounds
                .single()
                .links!!
                .jsonArray
                .size,
        )
    }

    @Test
    fun `treats JSON null string fields as absent`() {
        val etl = CampflareCampgroundsEtl()
        val out =
            etl.transform(
                etl.parse(
                    bundle(
                        "campflare-campgrounds",
                        """
                        [
                          {"id":"json-null-kind","name":"Null Kind","kind":null,"location":{"latitude":1,"longitude":2}}
                        ]
                        """.trimIndent(),
                    ),
                ),
                transformCtx(),
            )

        assertNull(out.campgrounds.single().kind)
    }

    private fun bundle(
        slug: String,
        payloadJson: String,
    ): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf(slug to listOf(envelope(payloadJson))),
            etlOutputs = linkedMapOf(),
        )

    private fun envelope(payloadJson: String): Envelope =
        Envelope(
            fetcher = "fetch_campflare_dump",
            fetcherVersion = "1",
            fetchedAt = "2026-07-08T00:00:00Z",
            request = RequestMeta(url = "https://api.campflare.com/dumps/latest/campgrounds", method = "GET"),
            response = ResponseMeta(status = 200),
            payload = Json.parseToJsonElement(payloadJson),
            part = "part-000001",
        )

    private fun transformCtx(): TransformCtx =
        TransformCtx.load(
            rawDir = File("build/tmp/cf-campgrounds-etl-test-raw"),
            registry = PoiRegistry(dataSources = emptyList(), poiData = emptyList()),
        )

    private fun campgroundPayload(): String =
        """
        [
          {
            "id": "upper-pines-campground-447",
            "name": " Upper Pines ",
            "status": "open",
            "status_description": "Open for the season",
            "kind": "established",
            "short_description": "A Yosemite campground.",
            "medium_description": "A longer Yosemite campground summary.",
            "long_description": "A generated Yosemite campground narrative.",
            "location": {
              "latitude": 37.739,
              "longitude": -119.565,
              "elevation": 4000,
              "address": {"state_code": "CA", "country_code": "US"}
            },
            "default_campsite_schedule": {"check_in_time": "14:00", "uniform": true},
            "amenities": {"toilets": true, "water": true},
            "max_rv_length": 35,
            "reservation_url": "https://www.recreation.gov/camping/campgrounds/232447",
            "links": [{"url": "https://www.nps.gov/yose", "title": "NPS"}],
            "photos": [{"original_url": "https://cdn.example/p.jpg"}],
            "price": {"minimum": 36, "currency_code": "USD"},
            "cell_service": {"verizon": 0.6},
            "management": {"agency_name": "National Park Service"},
            "contact": {"primary_phone": "555-0100"},
            "connections": {"ridb_facility_id": "232447"},
            "metadata": {"last_updated": "2026-07-01T00:00:00Z"}
          }
        ]
        """.trimIndent()
}
