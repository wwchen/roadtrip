package ca.floo.roadtrip.models.metadata.ingest

// Vocabulary:
//   fetch  = web → local file in data/raw/   (Phase.Fetch, runs a fetcher subprocess)
//   import = data/raw/ + data/etl-out/ → Postgres rows  (Phase.Import, runs EtlOrchestrator)
//
// "ingest" is the umbrella term for fetch + import together; it appears only
// in internal code (this package, the ingest_runs table). End-user surfaces
// (Make targets, Tilt buttons, README) say data-fetch / data-import.
sealed interface Phase {
    val label: String

    data class Fetch(
        override val label: String,
        val cmd: List<String>,
        val timeoutSec: Long = 30 * 60,
    ) : Phase

    /**
     * Run one row from the registry. The [section] field tells the
     * orchestrator which dispatch path to take:
     *   - POI_DATA              → runPoiData(name)
     *   - CAMPSITE_DATA         → runCampsiteData(name)
     *   - CAMPSITE_PARENT_JOINER → runJoiner(name), a reconciler that
     *                             reparents campsites whose canonical
     *                             campground_id disagrees with the joiner's
     *                             vendor-ref lookup.
     *
     * [name] is the row's display name from the YAML (unique per
     * section, but slugs share a namespace across sections so the
     * controller can route by name + section together).
     */
    data class Import(
        override val label: String,
        val name: String,
        val section: Section = Section.POI_DATA,
    ) : Phase {
        /** Which YAML section this import belongs to. */
        enum class Section(
            val rowValue: String,
        ) {
            POI_DATA("poi_data"),
            CAMPSITE_DATA("campsite_data"),
            CAMPSITE_PARENT_JOINER("campsite_parent_joiner"),
        }
    }
}
