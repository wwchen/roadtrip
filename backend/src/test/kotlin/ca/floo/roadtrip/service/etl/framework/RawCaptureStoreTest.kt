package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.model.metadata.registry.Fetcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals

class RawCaptureStoreTest {
    @TempDir
    lateinit var staticDir: File

    private val rawDir: File
        get() = staticDir.resolve("data/raw")

    @Test
    fun `loads captures from configured output dir prefix instead of slug dir`() {
        writeRaw(slug = "recgov-campsites")
        val source =
            dataSource(
                slug = "recgov-campsites-raw",
                outputDirPrefix = "data/raw/recgov-campsites",
            )

        val envelopes =
            RawCaptureStore(rawDir = rawDir, staticDir = staticDir)
                .loadNewestEnvelopes(source)

        assertEquals(1, envelopes.size)
        assertEquals("fetch_test", envelopes.single().fetcher)
    }

    @Test
    fun `rejects configured output dir prefix outside raw dir`() {
        val source =
            dataSource(
                slug = "bad-source",
                outputDirPrefix = "data/not-raw/bad-source",
            )

        val error =
            assertThrows<IllegalArgumentException> {
                RawCaptureStore(rawDir = rawDir, staticDir = staticDir).loadNewestEnvelopes(source)
            }

        assertContains(error.message!!, "resolves outside raw dir")
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
