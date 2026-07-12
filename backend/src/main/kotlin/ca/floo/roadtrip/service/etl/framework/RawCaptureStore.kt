package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.models.metadata.Envelope
import kotlinx.serialization.json.Json
import java.io.File

// Filesystem store for captured upstream envelopes under data/raw/<source>/.
// A capture is either one timestamped JSON file or a timestamped directory
// containing page-NNN.json files for paginated sources.
class RawCaptureStore(
    private val rawDir: File,
) {
    fun loadNewestEnvelopes(source: String): List<Envelope> {
        val dir = File(rawDir, source)
        if (!dir.isDirectory) throw NoCaptureException("$dir is not a directory")

        val newest =
            dir
                .listFiles { f -> f.isDirectory || (f.isFile && f.name.endsWith(".json")) }
                ?.maxByOrNull { it.name }
                ?: throw NoCaptureException("no captures under $dir")

        return if (newest.isDirectory) {
            loadPages(newest)
        } else {
            listOf(parseEnvelope(newest))
        }
    }

    private fun loadPages(captureDir: File): List<Envelope> {
        val pages =
            captureDir
                .listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?: emptyList()
        if (pages.isEmpty()) throw NoCaptureException("no pages under $captureDir")
        return pages.map { parseEnvelope(it) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse a specific envelope file, used by ETL fixture tests. */
        fun parseEnvelope(file: File): Envelope = json.decodeFromString(Envelope.serializer(), file.readText())
    }
}
