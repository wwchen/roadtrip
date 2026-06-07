package ca.floo.roadtrip.ingest

import java.io.File

// Static target map. One entry per coherent on-disk artifact; fetch + import
// phases live in the same Target so the per-target mutex serializes them
// (you can't import a half-written campgrounds.geojson). Adding a new
// upstream = appending an entry. No admin-API or schema change.
//
// `repoRoot` is the working directory shell phases run in (so paths like
// `scripts/fetch_planet_fitness.py` resolve correctly).
//
// **RFC 0007 transition.** Fetchers are now thin envelope-only writers
// landing under `data/raw/<source>/`. The Kotlin importer still reads
// the frozen `data/*.geojson` snapshots until the new ETL ships; that
// frozen-bridge is why the import phases below haven't moved yet.
// Triggering a fetch repopulates the raw cache; the importer is a no-op
// until ETL replaces it.
fun defaultTargets(repoRoot: File): Map<String, Target> {
    val python = listOf("python3")

    fun script(name: String) = listOf(repoRoot.resolve("scripts/$name").absolutePath)

    val targets =
        listOf(
            Target(
                name = "planet-fitness",
                fetchPhases = listOf(Phase.Fetch("fetch_planet_fitness.py", python + script("fetch_planet_fitness.py"))),
                importPhases = listOf(Phase.Import("import:osm-pf", "osm-pf")),
            ),
            Target(
                name = "state-parks",
                fetchPhases =
                    listOf(
                        Phase.Fetch(
                            "fetch_parks.py state",
                            python + script("fetch_parks.py") + listOf("--layer", "state-parks"),
                        ),
                    ),
                importPhases = listOf(Phase.Import("import:state-parks", "state-parks")),
            ),
            Target(
                name = "national-parks",
                fetchPhases =
                    listOf(
                        Phase.Fetch(
                            "fetch_parks.py national",
                            python + script("fetch_parks.py") + listOf("--layer", "national-parks"),
                        ),
                    ),
                importPhases = listOf(Phase.Import("import:national-parks", "national-parks")),
            ),
            // Two upstream sources for the campgrounds layer: uscampgrounds
            // CSVs and BC Parks Strapi. Each writes its own raw capture;
            // merging happens in the Kotlin ETL (RFC 0007).
            Target(
                name = "campgrounds",
                fetchPhases =
                    listOf(
                        Phase.Fetch("fetch_campgrounds.py", python + script("fetch_campgrounds.py")),
                        Phase.Fetch("fetch_bc_parks.py", python + script("fetch_bc_parks.py")),
                    ),
                importPhases = listOf(Phase.Import("import:uscampgrounds", "uscampgrounds")),
            ),
            // Aspira NextGen `/api/maps` for PC + BC + WA. Used by the ETL
            // to bind aspira IDs to campground rows by aspira_park_title
            // exact match. Fetch-only (no import) — aspira IDs land on
            // campground rows during the campground import.
            Target(
                name = "aspira-maps",
                fetchPhases =
                    listOf(
                        Phase.Fetch("fetch_aspira_maps.py", python + script("fetch_aspira_maps.py")),
                    ),
                importPhases = emptyList(),
            ),
            // Curated JSON files committed to the repo (under data/curated/).
            // No fetch phase — the importer reads them directly.
            Target(
                name = "parks-canada-curated",
                fetchPhases = emptyList(),
                importPhases = listOf(Phase.Import("import:parks-canada", "parks-canada")),
            ),
            Target(
                name = "alberta-provincial",
                fetchPhases = emptyList(),
                importPhases = listOf(Phase.Import("import:alberta-provincial", "alberta-provincial")),
            ),
            // Tesla supercharger feed: 2-stage capture (bulk index, then
            // per-slug enrichment for slugs lacking a recent capture).
            // No import yet — the existing /api/pricing route serves the
            // legacy data/pricing-cache/ files until the ETL ships.
            Target(
                name = "tesla-superchargers",
                fetchPhases =
                    listOf(
                        Phase.Fetch("fetch_tesla_index.py", python + script("fetch_tesla_index.py")),
                        Phase.Fetch("fetch_tesla_locations.py", python + script("fetch_tesla_locations.py")),
                    ),
                importPhases = emptyList(),
            ),
            // Rec.gov enrichment for federal campgrounds. NOT yet rewritten
            // for RFC 0007 — it still mutates data/campgrounds.geojson in
            // place. Lives as its own target so it can run on demand
            // against the frozen geojson during the bridge period; will
            // be reshaped to thin captures (data/raw/recgov-search/,
            // data/raw/recgov-aggregate/) when the Kotlin ETL ships.
            Target(
                name = "enrich-campgrounds",
                fetchPhases =
                    listOf(
                        Phase.Fetch("enrich_campgrounds.py", python + script("enrich_campgrounds.py")),
                    ),
                importPhases = emptyList(),
            ),
        )
    return targets.associateBy { it.name }
}
