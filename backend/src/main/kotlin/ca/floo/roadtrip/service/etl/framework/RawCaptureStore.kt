package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.registry.DataSourceEntry
import ca.floo.roadtrip.support.NoCaptureException
import kotlinx.serialization.json.Json
import java.io.File

private const val DATA_RAW_PREFIX = "data/raw/"
private const val RAW_PREFIX = "raw/"

// Filesystem store for captured upstream envelopes.
// A capture is either one timestamped JSON file or a timestamped directory
// containing page-NNN.json files for paginated sources.
class RawCaptureStore(
    private val rawDir: File,
) {
    fun loadNewestEnvelopes(source: String): List<Envelope> {
        val dir = File(rawDir, source)
        return loadNewestEnvelopesFrom(dir)
    }

    fun loadNewestEnvelopes(source: DataSourceEntry): List<Envelope> {
        val dir = captureDirFor(source)
        return loadNewestEnvelopesFrom(dir)
    }

    private fun captureDirFor(source: DataSourceEntry): File {
        val configured = File(source.fetcher.outputDirPrefix)
        if (configured.isAbsolute) return configured

        val relative = source.fetcher.outputDirPrefix.replace('\\', '/')
        val underRawDir =
            when {
                relative.startsWith(DATA_RAW_PREFIX) -> relative.removePrefix(DATA_RAW_PREFIX)
                relative.startsWith(RAW_PREFIX) -> relative.removePrefix(RAW_PREFIX)
                else -> relative
            }
        return File(rawDir, underRawDir)
    }

    private fun loadNewestEnvelopesFrom(dir: File): List<Envelope> {
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
