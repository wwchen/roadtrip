package ca.floo.roadtrip.service.etl.vendors.tesla

import ca.floo.roadtrip.model.domain.TeslaSuperchargerUpsertCandidate
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.model.metadata.registry.PoiRegistry
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TeslaIndexEtlTest {
    @TempDir
    lateinit var rawDir: File

    @Test
    fun `a site with no name is named by its slug as written and one with no openToPublic is counted as assumed open`() {
        writeDetail("woodburn", """{"name":"Woodburn, OR","openToPublic":false}""")
        writeDetail("salem", """{"name":"Salem, OR"}""")

        val results = transform(listOf("woodburn", "salem", "Never/Fetched")).associateBy { it.record.locationSlug }

        assertEquals("Never/Fetched", results.getValue("never-fetched").record.commonSiteName)
        assertEquals(true, results.getValue("never-fetched").record.openToPublic)
        assertEquals(listOf(NAMED_BY_SLUG, OPEN_TO_PUBLIC_UNLISTED), results.getValue("never-fetched").warnings)
        assertEquals(listOf(OPEN_TO_PUBLIC_UNLISTED), results.getValue("salem").warnings)
        assertEquals(false, results.getValue("woodburn").record.openToPublic)
        assertEquals(emptyList(), results.getValue("woodburn").warnings)
    }

    private fun writeDetail(
        slug: String,
        detail: String,
    ) {
        val dir = File(rawDir, "tesla-locations/$slug").apply { mkdirs() }
        File(dir, CAPTURE_FILE).writeText(
            """
            {"fetcher":"fetch_tesla_locations","fetcher_version":"1","fetched_at":"2026-06-09T15:33:15Z",
             "request":{"url":"https://example.invalid","method":"GET"},"response":{"status":200},
             "payload":{"data":{"data":$detail}}}
            """.trimIndent(),
        )
    }

    private fun transform(slugs: List<String>): List<TransformResult.Ok<TeslaSuperchargerUpsertCandidate>> {
        val rows =
            slugs.map { slug ->
                TeslaIndexRow(
                    latitude = LATITUDE,
                    longitude = LONGITUDE,
                    title = "locations",
                    locationType = listOf("supercharger"),
                    locationUrlSlug = slug,
                    superchargerFunction = TeslaSuperchargerFunction(showOnFindUs = "1", siteStatus = "open"),
                )
            }
        val ctx = TransformCtx.load(rawDir, PoiRegistry(dataSources = emptyList(), poiData = emptyList()))
        return TeslaIndexEtl()
            .transform(TeslaIndexDto(rows, emptyMap(), Instant.EPOCH), ctx)
            .filterIsInstance<TransformResult.Ok<TeslaSuperchargerUpsertCandidate>>()
            .toList()
    }

    private companion object {
        const val CAPTURE_FILE = "2026-06-09T15-33-15Z.json"
        const val LATITUDE = 44.1
        const val LONGITUDE = -123.2
        const val NAMED_BY_SLUG = "Tesla site has no name; named by its location slug"
        const val OPEN_TO_PUBLIC_UNLISTED = "Tesla openToPublic not listed; stored as open to the public"
    }
}
