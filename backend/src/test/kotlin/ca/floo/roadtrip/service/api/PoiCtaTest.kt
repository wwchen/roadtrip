package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.repo.PoiDetailRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PoiCtaTest {
    @Test
    fun `recgov reservable provider_ref produces booking URL`() {
        val cta = PoiCta.computeCta(row(providerRefJson = """{"recgov_id":"232450"}"""))
        assertEquals("https://www.recreation.gov/camping/campgrounds/232450", cta?.url)
        assertEquals("Reserve on recreation.gov", cta?.label)
        assertEquals("reserve", cta?.kind)
    }

    @Test
    fun `aspira provider_ref returns null because dated deeplink lives on the FE`() {
        val cta =
            PoiCta.computeCta(
                row(providerRefJson = """{"transactionLocationId":1,"mapId":2,"resourceLocationId":null}"""),
            )
        assertNull(cta)
    }

    @Test
    fun `non-reservable Forest Service campground uses RIDB official URL`() {
        // POI 441 (Butte Meadows) shape: no provider_ref, info_url points at fs.usda.gov
        val cta =
            PoiCta.computeCta(
                row(infoUrl = "https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276"),
            )
        assertEquals("https://www.fs.usda.gov/recarea/lassen/recarea/?recid=11276", cta?.url)
        assertEquals("Park info on fs.usda.gov", cta?.label)
        assertEquals("info", cta?.kind)
    }

    @Test
    fun `info_url with unrecognized host falls back to bare host label`() {
        val cta = PoiCta.computeCta(row(infoUrl = "https://example.org/some/page"))
        assertEquals("Visit example.org", cta?.label)
    }

    @Test
    fun `www prefix in host is stripped before label lookup`() {
        val cta = PoiCta.computeCta(row(infoUrl = "https://www.nps.gov/yose/index.htm"))
        assertEquals("Park info on nps.gov", cta?.label)
    }

    @Test
    fun `no provider_ref and no info_url returns null`() {
        assertNull(PoiCta.computeCta(row()))
    }

    @Test
    fun `blank info_url returns null`() {
        assertNull(PoiCta.computeCta(row(infoUrl = "  ")))
    }

    @Test
    fun `provider_ref recgov wins over info_url`() {
        // A reservable rec.gov campground also has its rec.gov page as info_url.
        // We want the canonical "Reserve on recreation.gov" CTA, not the page link.
        val cta =
            PoiCta.computeCta(
                row(
                    providerRefJson = """{"recgov_id":"232450"}""",
                    infoUrl = "https://www.recreation.gov/camping/campgrounds/232450",
                ),
            )
        assertEquals("Reserve on recreation.gov", cta?.label)
        assertEquals("reserve", cta?.kind)
    }

    private fun row(
        providerRefJson: String? = null,
        infoUrl: String? = null,
    ): PoiDetailRow =
        PoiDetailRow(
            id = 441L,
            source = "federal-campgrounds",
            sourceId = "recgov-248965",
            category = "campground",
            subcategory = "federal",
            name = "Butte Meadows Campground",
            region = "CA",
            unitName = null,
            reserveUrl = null,
            phone = null,
            infoUrl = infoUrl,
            addressJson = null,
            providerRefJson = providerRefJson,
            geomJson = """{"type":"Point","coordinates":[-121.5,40.0]}""",
            propertiesJson = "{}",
        )
}
