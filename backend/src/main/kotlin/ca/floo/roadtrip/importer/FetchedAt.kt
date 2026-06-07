package ca.floo.roadtrip.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

private val log = LoggerFactory.getLogger("FetchedAt")

// Reads the _fetched_at sentinel from a parsed JSON/GeoJSON root, falling
// back to file.lastModified() when absent or unparseable.
//
// Why we need this: file.lastModified() reflects when the file was last
// *written* to disk. After a `git pull` on the deploy box that's "today,"
// even if the upstream HTTP fetch was months ago. Each Python fetcher now
// stamps an ISO-8601 UTC `_fetched_at` at the top of its output; this helper
// pulls that out so pois.fetched_at reflects upstream-fetch time.
//
// Backwards compatible: any data file that pre-dates the fetcher change
// won't have the key, and we fall back transparently. Once every Source
// has been refreshed once with the new fetcher, the fallback becomes dead
// code we can delete.
fun readFetchedAt(
    root: JsonObject,
    file: File,
): Instant {
    val raw = root["_fetched_at"]?.jsonPrimitive?.contentOrNull
    if (raw.isNullOrBlank()) return Instant.ofEpochMilli(file.lastModified())
    return try {
        Instant.parse(raw)
    } catch (e: DateTimeParseException) {
        log.warn("ignoring unparseable _fetched_at='{}' in {}; falling back to mtime", raw, file.name)
        Instant.ofEpochMilli(file.lastModified())
    }
}
