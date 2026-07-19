package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.model.domain.DataProvider
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveAmericaEtlTest {
    private val kotlinx.serialization.json.JsonObject.contractCode: String
        get() = this["contract_code"]!!.jsonPrimitive.content

    private val kotlinx.serialization.json.JsonObject.parkId: String
        get() = this["park_id"]!!.jsonPrimitive.content

    @Test
    fun `new york args stamp state campground metadata with reserveamerica provider ref`() {
        val etl = ReserveAmericaEtl("new-york-state-parks")
        val dto = etl.parse(bundle("reserveamerica-ny", nyParkEnvelope()))
        assertEquals("ALGER ISLAND, NY", dto.parks.single().name)

        val campground = etl.transform(dto, transformCtx()).campgrounds.single()

        assertEquals(DataProvider.RESERVEAMERICA, campground.dataProvider)
        assertEquals("ra-695", campground.dataProviderRef)
        assertEquals("ALGER ISLAND", campground.name)
        val location = campground.location!!.jsonObject
        assertEquals("NY", location["region"]!!.jsonPrimitive.content)
        assertEquals("US", location["country"]!!.jsonPrimitive.content)
        assertEquals("state", campground.kind)
        val management = campground.management!!.jsonObject
        assertEquals("New York State Parks", management["agency"]!!.jsonPrimitive.content)
        assertEquals(
            "https://newyorkstateparks.reserveamerica.com/camping/alger-island/r/campgroundDetails.do?contractCode=NY&parkId=695",
            campground.reservationUrl,
        )
        assertEquals("Island campground on Fourth Lake.", campground.mediumDescription)
        assertEquals("https://newyorkstateparks.reserveamerica.com/photo.jpg", campground.photos!!.jsonObjectArrayFirstUrl())

        val metadata = campground.metadata!!.jsonObject
        assertEquals("NY", metadata["contract"]!!.jsonPrimitive.content)
        assertEquals("ALGER ISLAND", metadata["name"]!!.jsonPrimitive.content)
        assertEquals("Island campground on Fourth Lake.", metadata["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `alberta defaults preserve existing source shape and reserveamerica provider ref`() {
        val campground =
            ReserveAmericaEtl()
                .transform(
                    ReserveAmericaDto(
                        parks =
                            listOf(
                                ParsedPark(
                                    parkId = 123,
                                    name = "Writing-on-Stone Provincial Park, AB",
                                    lat = 49.083,
                                    lon = -111.617,
                                    phone = null,
                                    description = null,
                                    photoUrl = null,
                                    infoUrl = "https://shop.albertaparks.ca/camping/x/r/campgroundDetails.do?contractCode=ABPP&parkId=123",
                                ),
                            ),
                        fetchedAt = fetchedAt,
                    ),
                    transformCtx(),
                ).campgrounds
                .single()

        assertEquals(DataProvider.RESERVEAMERICA, campground.dataProvider)
        assertEquals("ra-123", campground.dataProviderRef)
        assertEquals("Writing-on-Stone Provincial Park", campground.name)
        val location = campground.location!!.jsonObject
        assertEquals("AB", location["region"]!!.jsonPrimitive.content)
        assertEquals("CA", location["country"]!!.jsonPrimitive.content)
        assertEquals("provincial", campground.kind)
        val management = campground.management!!.jsonObject
        assertEquals("Alberta Parks", management["agency"]!!.jsonPrimitive.content)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectArrayFirstUrl(): String =
        this
            .jsonArray
            .first()
            .jsonObject["url"]!!
            .jsonPrimitive.content

    private fun nyParkEnvelope(): Envelope {
        val url =
            "https://newyorkstateparks.reserveamerica.com/camping/alger-island/r/campgroundDetails.do?contractCode=NY&parkId=695"
        return Envelope(
            fetcher = "fetch_reserveamerica",
            fetcherVersion = "1",
            fetchedAt = fetchedAt.toString(),
            request =
                RequestMeta(
                    url = url,
                    method = "GET",
                ),
            response = ResponseMeta(status = 200),
            payload =
                JsonPrimitive(
                    """
                    <html>
                      <head>
                        <meta property="place:location:latitude" content='43.7597'>
                        <meta property="place:location:longitude" content='-74.7244'>
                        <meta property="og:title" content='ALGER ISLAND, NY'>
                        <meta property="og:description" content='Island campground on Fourth Lake.'>
                        <meta property="og:image" content='https://newyorkstateparks.reserveamerica.com/photo.jpg'>
                        <meta property="og:url" content='https://newyorkstateparks.reserveamerica.com/camping/alger-island/r/campgroundDetails.do?contractCode=NY&parkId=695'>
                      </head>
                      <body><span itemprop="telephone">1-518-555-0100</span></body>
                    </html>
                    """.trimIndent(),
                ),
            part = "park-695",
        )
    }

    private fun bundle(
        slug: String,
        envelope: Envelope,
    ): InputBundle = InputBundle(linkedMapOf(slug to listOf(envelope)), linkedMapOf())

    private fun transformCtx(): TransformCtx =
        TransformCtx.load(File("build/tmp/reserveamerica-etl-test-raw"), PoiRegistry.loadResource("poi-registry.yaml"))

    private companion object {
        val fetchedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
