package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.domain.Address
import ca.floo.roadtrip.model.domain.CampgroundContact
import ca.floo.roadtrip.model.domain.CampgroundLink
import ca.floo.roadtrip.model.domain.CampgroundManagement
import ca.floo.roadtrip.model.domain.CampgroundPhoto
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
        val rows = terminalRecords(etl, bundle("campflare-campgrounds", campgroundPayload()), transformCtx())
        val row = rows.single()

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
        val campflareLink = row.links.last()
        assertEquals("232447", ridbFacilityId)
        assertEquals(true, hasToilets)
        assertEquals(" Upper Pines ", sourceName)
        assertEquals("Campflare source", campflareLink.title)
        assertEquals("https://campflare.com/campground/upper-pines-campground-447", campflareLink.url)
        assertEquals(BookingProvider.RECGOV, row.bookingProvider)
        assertEquals("232447", row.bookingProviderRef)
    }

    @Test
    fun `maps management agency_name to the canonical agency`() {
        val etl = CampflareCampgroundsEtl()
        val rows = terminalRecords(etl, bundle("campflare-campgrounds", campgroundPayload()), transformCtx())

        // Serving query and every other vendor read management->>'agency'.
        // The upstream keys stay in source_payload, which the drawer renders.
        assertEquals(CampgroundManagement("National Park Service"), rows.single().management)
    }

    @Test
    fun `maps the Campflare photo, contact, elevation and address keys`() {
        val etl = CampflareCampgroundsEtl()
        val row = terminalRecords(etl, bundle("campflare-campgrounds", campgroundPayload()), transformCtx()).single()

        assertEquals(listOf(CampgroundPhoto("https://cdn.example/p.jpg")), row.photos)
        assertEquals(CampgroundContact(phone = "555-0100"), row.contact)
        assertEquals(4000.0, row.location.elevation)
        assertEquals(Address(state = "CA", country = "US"), row.location.address)
    }

    @Test
    fun `leaves management null when upstream names no agency`() {
        val etl = CampflareCampgroundsEtl()
        val rows =
            terminalRecords(
                etl,
                bundle(
                    "campflare-campgrounds",
                    """
                    [
                      {"id":"no-agency","name":"No Agency","location":{"latitude":1,"longitude":2}}
                    ]
                    """.trimIndent(),
                ),
                transformCtx(),
            )

        assertNull(rows.single().management)
    }

    @Test
    fun `skips campground rows without id name or coordinates`() {
        val etl = CampflareCampgroundsEtl()
        val rows =
            terminalOkRecords(
                etl,
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
                transformCtx(),
            )

        assertEquals(listOf("ok"), rows.map { it.dataProviderRef.serialize() })
    }

    @Test
    fun `adds campflare source link when campground dump has no links`() {
        val etl = CampflareCampgroundsEtl()
        val rows =
            terminalRecords(
                etl,
                bundle(
                    "campflare-campgrounds",
                    """
                    [
                      {"id":"no-links","name":"No Links","location":{"latitude":1,"longitude":2}}
                    ]
                    """.trimIndent(),
                ),
                transformCtx(),
            )

        assertEquals(
            listOf(CampgroundLink("https://campflare.com/campground/no-links", title = "Campflare source")),
            rows.single().links,
        )
    }

    @Test
    fun `does not duplicate existing campflare source href`() {
        val etl = CampflareCampgroundsEtl()
        val rows =
            terminalRecords(
                etl,
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
                transformCtx(),
            )

        assertEquals(1, rows.single().links.size)
    }

    @Test
    fun `treats JSON null string fields as absent`() {
        val etl = CampflareCampgroundsEtl()
        val rows =
            terminalRecords(
                etl,
                bundle(
                    "campflare-campgrounds",
                    """
                    [
                      {"id":"json-null-kind","name":"Null Kind","kind":null,"location":{"latitude":1,"longitude":2}}
                    ]
                    """.trimIndent(),
                ),
                transformCtx(),
            )

        assertNull(rows.single().kind)
    }

    private fun bundle(
        slug: String,
        payloadJson: String,
    ): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf(slug to listOf(envelope(payloadJson))),
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
