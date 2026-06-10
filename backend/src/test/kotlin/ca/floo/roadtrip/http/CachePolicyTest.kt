package ca.floo.roadtrip.http

import io.ktor.http.ContentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CachePolicyTest {
    @Test
    fun `mutable API responses are not browser cached`() {
        assertEquals(
            "no-store",
            cacheOptionsFor("/api/campsite/matches", ContentType.Application.Json)?.cacheControl.toString(),
        )
        assertEquals(
            "no-store",
            cacheOptionsFor("/api/campsite/alerts", ContentType.Application.Json)?.cacheControl.toString(),
        )
        assertEquals(
            "no-store",
            cacheOptionsFor("/api/pois/search", ContentType.Application.Json)?.cacheControl.toString(),
        )
    }

    @Test
    fun `poi detail route keeps its route-level cache header`() {
        assertNull(cacheOptionsFor("/api/pois/123", ContentType.Application.Json))
    }

    @Test
    fun `static assets keep deploy-friendly cache policy`() {
        assertEquals(
            "no-cache",
            cacheOptionsFor("/web/app.js", ContentType.Text.JavaScript)?.cacheControl.toString(),
        )
        assertEquals(
            "max-age=86400",
            cacheOptionsFor("/data/campgrounds.json", ContentType.Application.Json)?.cacheControl.toString(),
        )
    }
}
