package ca.floo.roadtrip.service.etl.vendors.bcparks

import ca.floo.roadtrip.model.domain.DataProvider
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class BcParksStrapiEtlTest {
    @Test
    fun `transform promotes BC Parks description and featured photo`() {
        val etl = BcParksStrapiEtl()
        val campground = etl.transform(etl.parse(bundle()), transformCtx()).campgrounds.single()

        assertEquals(DataProvider.STRAPI, campground.dataProvider)
        assertEquals("orcs-6648", campground.dataProviderRef)
        assertEquals("<p>Camp beside Adams Lake.</p>", campground.mediumDescription)
        assertEquals(
            "https://example.test/featured.jpg",
            campground.photos!!
                .jsonArray
                .first()
                .jsonObject["url"]!!
                .jsonPrimitive
                .content,
        )
        val location = campground.location!!.jsonObject
        assertEquals("BC", location["region"]!!.jsonPrimitive.content)
        assertEquals("CA", location["country"]!!.jsonPrimitive.content)
        val management = campground.management!!.jsonObject
        assertEquals("BC Parks", management["agency"]!!.jsonPrimitive.content)
    }

    private fun bundle(): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("bcparks-strapi" to listOf(envelope())),
            etlOutputs = linkedMapOf(),
        )

    private fun envelope(): Envelope =
        Envelope(
            fetcher = "fetch_bcparks_strapi",
            fetcherVersion = "1",
            fetchedAt = "2026-06-09T00:00:00Z",
            request = RequestMeta(url = "https://bcparks.example.test", method = "GET"),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    {
                      "data": [
                        {
                          "orcs": 6648,
                          "protectedAreaName": "Adams Lake Marine Park",
                          "legalStatus": "Active",
                          "url": "https://bcparks.ca/adams-lake-marine-park/",
                          "latitude": 51.1746684,
                          "longitude": -119.5707686,
                          "description": "<p>Camp beside Adams Lake.</p>",
                          "parkPhotos": [
                            {
                              "imageUrl": "https://example.test/inactive-featured.jpg",
                              "isActive": false,
                              "isFeatured": true,
                              "sortOrder": 1
                            },
                            {
                              "imageUrl": "https://example.test/featured.jpg",
                              "isActive": true,
                              "isFeatured": true,
                              "sortOrder": 10
                            },
                            {
                              "imageUrl": "https://example.test/first-active.jpg",
                              "isActive": true,
                              "isFeatured": false,
                              "sortOrder": 1
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

    private fun transformCtx(): TransformCtx =
        TransformCtx.load(File("build/tmp/bcparks-strapi-etl-test-raw"), PoiRegistry.loadResource("poi-registry.yaml"))
}
