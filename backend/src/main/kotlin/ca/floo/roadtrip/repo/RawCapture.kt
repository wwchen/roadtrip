package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.Envelope
import kotlinx.serialization.json.Json
import java.io.File

// Filesystem helper: pick the newest envelope under data/raw/<source>/.
// The filename format is monotonic (YYYY-MM-DDTHH-MM-SSZ.json), so the
// "newest" is the lexicographically-greatest entry. RFC decision #15 —
// no `latest` symlink to maintain.
//
// Multi-part captures (paginated sources like PAD-US, BC Strapi) live
// under <source>/<ts>/<part>.json. The newest <ts>/ directory holds
// every page of one logical capture.
object RawCapture {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the newest single-file capture under [rawDir] / [source] /
     * (e.g. `data/raw/osm-pf/2026-06-07T21-33-18Z.json`), parsed into an
     * Envelope.
     *
     * @throws NoCaptureException if no captures exist for this source.
     */
    fun newestSingle(
        rawDir: File,
        source: String,
    ): Envelope {
        val dir = File(rawDir, source)
        if (!dir.isDirectory) throw NoCaptureException("$dir is not a directory")
        val newest =
            dir
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.maxByOrNull { it.name }
                ?: throw NoCaptureException("no captures under $dir")
        return parseEnvelope(newest)
    }

    /**
     * Returns every page of the newest multi-part capture under
     * [rawDir] / [source] / (e.g. `data/raw/padus-np/<ts>/page-NNN.json`).
     * Pages are returned in lexical order, which matches `page-001`,
     * `page-002`, ... ordering.
     *
     * Useful for paginated sources where the ETL needs to stitch pages
     * back together at parse time.
     *
     * @throws NoCaptureException if no captures exist for this source.
     */
    fun newestMultiPart(
        rawDir: File,
        source: String,
    ): List<Envelope> {
        val dir = File(rawDir, source)
        if (!dir.isDirectory) throw NoCaptureException("$dir is not a directory")
        val newestSubdir =
            dir
                .listFiles { f -> f.isDirectory }
                ?.maxByOrNull { it.name }
                ?: throw NoCaptureException("no per-capture subdirs under $dir")
        val pages =
            newestSubdir
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?: emptyList()
        if (pages.isEmpty()) throw NoCaptureException("no pages under $newestSubdir")
        return pages.map { parseEnvelope(it) }
    }

    /** Parse a specific envelope file (used by tests against fixtures). */
    fun parseEnvelope(file: File): Envelope = json.decodeFromString(Envelope.serializer(), file.readText())
}

class NoCaptureException(
    message: String,
) : RuntimeException(message)
