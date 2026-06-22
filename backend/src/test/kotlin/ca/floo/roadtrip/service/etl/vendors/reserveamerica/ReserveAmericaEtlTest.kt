package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.RequestMeta
import ca.floo.roadtrip.models.metadata.ResponseMeta
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveAmericaEtlTest {
    @Test
    fun `new york args stamp state campground metadata with reserveamerica provider ref`() {
        val etl = ReserveAmericaEtl("new-york-state-parks")
        val dto = etl.parse(bundle("reserveamerica-ny", nyParkEnvelope()))
        assertEquals("ALGER ISLAND, NY", dto.parks.single().name)

        val poi = etl.transform(dto, transformCtx()).single()

        assertEquals("new-york-state-parks", poi.source)
        assertEquals("ra-695", poi.sourceId)
        assertEquals("ALGER ISLAND", poi.name)
        assertEquals("NY", poi.region)
        assertEquals("US", poi.country)
        assertEquals("state", poi.subcategory)
        assertEquals("New York State Parks", poi.agency)
        val ref = poi.providerRef as ProviderRef.ReserveAmerica
        assertEquals("NY", ref.contractCode)
        assertEquals("695", ref.parkId)
        assertEquals(
            "https://newyorkstateparks.reserveamerica.com/camping/alger-island/r/campgroundDetails.do?contractCode=NY&parkId=695",
            poi.infoUrl,
        )

        val extras = poi.extras!!.jsonObject
        assertEquals("NY", extras["contract"]!!.jsonPrimitive.content)
        assertEquals("ALGER ISLAND", extras["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `alberta defaults preserve existing source shape and reserveamerica provider ref`() {
        val poi =
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
                                    photoUrl = null,
                                    infoUrl = "https://shop.albertaparks.ca/camping/x/r/campgroundDetails.do?contractCode=ABPP&parkId=123",
                                ),
                            ),
                        fetchedAt = FETCHED_AT,
                    ),
                    transformCtx(),
                ).single()

        assertEquals("alberta-provincial", poi.source)
        assertEquals("ra-123", poi.sourceId)
        assertEquals("Writing-on-Stone Provincial Park", poi.name)
        assertEquals("AB", poi.region)
        assertEquals("CA", poi.country)
        assertEquals("provincial", poi.subcategory)
        assertEquals("Alberta Parks", poi.agency)
        val ref = poi.providerRef as ProviderRef.ReserveAmerica
        val extras = poi.extras!!.jsonObject
        assertEquals("ABPP", ref.contractCode)
        assertEquals("123", ref.parkId)
        assertEquals("ABPP", extras["contract"]!!.jsonPrimitive.content)
    }

    private fun nyParkEnvelope(): Envelope {
        val url =
            "https://newyorkstateparks.reserveamerica.com/camping/alger-island/r/campgroundDetails.do?contractCode=NY&parkId=695"
        return Envelope(
            fetcher = "fetch_reserveamerica",
            fetcherVersion = "1",
            fetchedAt = FETCHED_AT.toString(),
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

    private fun transformCtx(): TransformCtx {
        val yamlPath =
            File(System.getProperty("user.dir"))
                .resolve("../config/poi-registry.yaml")
                .canonicalFile
        return TransformCtx.load(File("build/tmp/reserveamerica-etl-test-raw"), PoiRegistry.load(yamlPath))
    }

    private companion object {
        val FETCHED_AT: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
