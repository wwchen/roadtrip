package ca.floo.roadtrip.service.poi.campground

import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalInfoLinkLabelsTest {
    private val labels = ExternalInfoLinkLabels(shippedTenantRegistry())

    @Test
    fun `a registered vendor host wins over the agency table`() {
        assertEquals("View on Recreation.gov", labels.forUrl("https://www.recreation.gov/camping/campgrounds/232450"))
    }

    @Test
    fun `a vendor subdomain shadows its agency row`() {
        assertEquals("View on BC Parks", labels.forUrl("https://camping.bcparks.ca/"))
    }

    @Test
    fun `an agency host keeps its agency label, www stripped`() {
        assertEquals("Park info on nps.gov", labels.forUrl("https://www.nps.gov/yose/index.htm"))
    }

    @Test
    fun `an unknown host falls back to the bare host`() {
        assertEquals("Visit example.org", labels.forUrl("https://example.org/some/page"))
    }

    @Test
    fun `a URL with no host we can read is named generically`() {
        assertEquals("Visit website", labels.forUrl("not a url"))
    }
}
