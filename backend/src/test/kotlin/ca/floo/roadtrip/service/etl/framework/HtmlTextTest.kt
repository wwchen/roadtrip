package ca.floo.roadtrip.service.etl.framework

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlTextTest {
    @Test
    fun `strips tags and collapses whitespace`() {
        assertEquals(
            "Walk-in tent site by the water.",
            HtmlText.stripTags("<p>Walk-in <b>tent</b> site\n by the water.</p>"),
        )
    }

    @Test
    fun `leaves plain text untouched`() {
        assertEquals("Quiet lakeside site", HtmlText.stripTags("Quiet lakeside site"))
    }
}
