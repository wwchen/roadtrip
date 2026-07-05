package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityParser
import ca.floo.roadtrip.models.metadata.Envelope
import ca.floo.roadtrip.models.metadata.RequestMeta
import ca.floo.roadtrip.models.metadata.ResponseMeta
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReserveAmericaSitesEtlTest {
    private val html =
        """
        <div class='siteListLabel'><a href="/x/campsiteDetails.do?contractCode=NY&amp;siteId=253478&amp;parkId=489" aria-label='Site: 039 (253478)'>039</a></div>
        <div class='td status a'>A</div>
        <div class='siteListLabel'><a href="/x/campsiteDetails.do?contractCode=NY&amp;siteId=253497&amp;parkId=489" aria-label='Site: 056 (253497)'>056</a></div>
        <div class='td status r'>R</div>
        """.trimIndent()

    private val yamlPath =
        File(System.getProperty("user.dir"))
            .resolve("../config/poi-registry.yaml")
            .canonicalFile

    private val transformCtx =
        TransformCtx.load(File("build/tmp/ra-sites-etl-test-raw"), PoiRegistry.load(yamlPath))

    private fun bundle(): InputBundle =
        InputBundle(
            linkedMapOf(
                "reserveamerica-campsites-ny" to
                    listOf(
                        Envelope(
                            fetcher = "fetch_reserveamerica_campsites",
                            fetcherVersion = "1",
                            fetchedAt = "2026-07-05T00:00:00Z",
                            request = RequestMeta(url = "https://x/campsiteCalendar.do", method = "GET"),
                            response = ResponseMeta(status = 200),
                            payload = JsonPrimitive(html),
                            part = "campsite-489-0",
                        ),
                    ),
            ),
            linkedMapOf(),
        )

    @Test
    fun `emits one site reservable per row with per-tenant vendor`() {
        val etl = ReserveAmericaSitesEtl(etlSlug = "new-york-state-park-sites", contractCode = "NY")
        val out = etl.transform(etl.parse(bundle()), transformCtx)

        assertEquals(2, out.reservables.size)
        val first = out.reservables.first()
        assertEquals("site:reserveamerica_ny:253478", first.rid.encode())
        assertEquals("039", first.name)
        assertEquals(null, first.loop)
        assertEquals(null, first.siteType)
        val raw = first.raw!!.jsonObject
        assertEquals("NY", raw["_parent_contract_code"]!!.jsonPrimitive.content)
        assertEquals("489", raw["_parent_park_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vendorId equals the availability parser siteId - binds by construction`() {
        val etl = ReserveAmericaSitesEtl(etlSlug = "new-york-state-park-sites", contractCode = "NY")
        val catalogIds =
            etl
                .transform(etl.parse(bundle()), transformCtx)
                .reservables
                .map { it.rid.vendorId }
                .toSet()
        val availabilityIds =
            ReserveAmericaAvailabilityParser
                .siteRows(html)
                .mapNotNull { Regex("""siteId=(\d+)""").find(it)?.groupValues?.get(1) }
                .toSet()
        assertEquals(availabilityIds, catalogIds)
        assertTrue(catalogIds.isNotEmpty())
    }
}
