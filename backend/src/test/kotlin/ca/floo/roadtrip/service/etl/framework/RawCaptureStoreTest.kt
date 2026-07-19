package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.model.metadata.registry.Fetcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class RawCaptureStoreTest {
    @TempDir
    lateinit var rawDir: File

    @Test
    fun `loads captures from configured output dir prefix instead of slug dir`() {
        writeRaw(slug = "recgov-campsites")
        val source =
            dataSource(
                slug = "recgov-campsites-raw",
                outputDirPrefix = "data/raw/recgov-campsites",
            )

        val envelopes = RawCaptureStore(rawDir).loadNewestEnvelopes(source)

        assertEquals(1, envelopes.size)
        assertEquals("fetch_test", envelopes.single().fetcher)
    }

    private fun writeRaw(slug: String) {
        val dir = rawDir.resolve(slug).resolve("2026-07-19T00-00-00Z")
        dir.mkdirs()
        dir.resolve("part-000001.json").writeText(
            """
            {
              "fetcher":"fetch_test",
              "fetcher_version":"1",
              "fetched_at":"2026-07-19T00:00:00Z",
              "request":{"url":"https://example.test/$slug","method":"GET","headers":{}},
              "response":{"status":200,"headers":{}},
              "poller_run_id":null,
              "payload":{"ok":true},
              "part":"part-000001"
            }
            """.trimIndent(),
        )
    }

    private fun dataSource(
        slug: String,
        outputDirPrefix: String,
    ): DataSourceEntry =
        DataSourceEntry(
            slug = slug,
            name = slug,
            fetcher =
                Fetcher(
                    executor = "python3",
                    filename = "scripts/fetch_test.py",
                    outputDirPrefix = outputDirPrefix,
                ),
        )
}
