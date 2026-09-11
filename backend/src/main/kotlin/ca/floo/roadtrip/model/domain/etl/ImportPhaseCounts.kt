package ca.floo.roadtrip.model.domain.etl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Counts written into `ingest_runs.counts` for one import phase. Section-specific
 * fields are nullable; readers ignore the ones they don't care about. Existing
 * dashboards keyed off `seen`/`swept`/`import_run_id` keep working.
 */
@Serializable
data class ImportPhaseCounts(
    @SerialName("import_run_id") val importRunId: Long,
    val seen: Int,
    val swept: Int,
    @SerialName("terminal_etl") val terminalEtl: String,
    @SerialName("upserted_campsites") val upsertedCampsites: Int? = null,
    @SerialName("skipped_campsites") val skippedCampsites: Int? = null,
)
