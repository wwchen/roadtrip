package ca.floo.roadtrip.model.metadata.ingest

// Vocabulary:
//   import = data/raw/ + data/etl-out/ → Postgres rows  (Phase.Import, runs EtlOrchestrator)
//
// "ingest" is the umbrella term used by the ingest_runs table. Fetchers now
// run outside the backend process; backend phases are import-only.
sealed interface Phase {
    val label: String

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
