package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.RequestMeta
import ca.floo.roadtrip.model.metadata.ResponseMeta
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecGovCampsitesEtlTest {
    @Test
    fun `transform falls back to campsite id when recgov site fields are blank`() {
        val etl = RecGovCampsitesEtl("federal-campsites")
        val campsite = etl.transform(etl.parse(bundle()), transformCtx()).campsites.single()

        assertEquals("123456", campsite.vendorRefId)
        assertEquals("123456", campsite.name)
        assertEquals("site", campsite.kind)
        assertEquals("recgov", campsite.parentVendor)
        assertEquals("recgov-232447", campsite.parentVendorRefId)
        assertNull(campsite.kindListed)
        assertNull(campsite.loopName)
    }

    private fun bundle(): InputBundle =
        InputBundle(
            rawCaptures = linkedMapOf("recgov-campsites" to listOf(envelope())),
            etlOutputs = linkedMapOf(),
        )

    private fun envelope(): Envelope =
        Envelope(
            fetcher = "fetch_recgov_campsites",
            fetcherVersion = "1",
            fetchedAt = "2026-06-17T00:00:00Z",
            request =
                RequestMeta(
                    url = "https://www.recreation.gov/api/camps/availability/campground/232447/month?start_date=2026-06-01T00%3A00%3A00Z",
                    method = "GET",
                ),
            response = ResponseMeta(status = 200),
            payload =
                Json.parseToJsonElement(
                    """
                    {
                      "campsites": {
                        "123456": {
                          "site": "",
                          "loop": "",
                          "campsite_type": "",
                          "max_num_people": 8
                        }
                      }
                    }
                    """.trimIndent(),
                ),
        )

    private fun transformCtx(): TransformCtx =
        TransformCtx.load(File("build/tmp/recgov-campsites-etl-test-raw"), PoiRegistry.loadResource("poi-registry.yaml"))
}
