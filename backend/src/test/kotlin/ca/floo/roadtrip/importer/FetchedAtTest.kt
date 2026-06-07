package ca.floo.roadtrip.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchedAtTest {
    @Test
    fun `reads _fetched_at from the parsed root`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "f.json")
        file.writeText(
            """{"_fetched_at":"2026-01-15T08:30:00Z","type":"FeatureCollection","features":[]}""",
        )
        val root = Json.parseToJsonElement(file.readText()).jsonObject

        val parsed = readFetchedAt(root, file)

        assertEquals(Instant.parse("2026-01-15T08:30:00Z"), parsed)
    }

    @Test
    fun `falls back to file mtime when key is absent`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "f.json")
        file.writeText("""{"type":"FeatureCollection","features":[]}""")
        val mtime = Instant.parse("2025-03-04T12:00:00Z")
        file.setLastModified(mtime.toEpochMilli())
        val root = Json.parseToJsonElement(file.readText()).jsonObject

        val parsed = readFetchedAt(root, file)

        assertEquals(mtime, parsed)
    }

    @Test
    fun `falls back when value is unparseable`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "f.json")
        file.writeText("""{"_fetched_at":"yesterday","type":"FeatureCollection","features":[]}""")
        val mtime = Instant.parse("2025-06-01T00:00:00Z")
        file.setLastModified(mtime.toEpochMilli())
        val root = Json.parseToJsonElement(file.readText()).jsonObject

        val parsed = readFetchedAt(root, file)

        assertEquals(mtime, parsed)
    }

    @Test
    fun `falls back when value is blank`(
        @TempDir tmp: Path,
    ) {
        val file = File(tmp.toFile(), "f.json")
        file.writeText("""{"_fetched_at":"","type":"FeatureCollection","features":[]}""")
        val mtime = Instant.parse("2025-09-09T09:09:09Z")
        file.setLastModified(mtime.toEpochMilli())
        val root = Json.parseToJsonElement(file.readText()).jsonObject

        val parsed = readFetchedAt(root, file)

        // Blank string falls through to mtime; the warn log path is exercised
        // but the result is the same as the absent case.
        assertTrue(parsed == mtime, "expected mtime fallback, got $parsed")
    }
}
