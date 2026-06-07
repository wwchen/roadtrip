package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.importer.PadUsParksHttpSource
import ca.floo.roadtrip.importer.PlanetFitnessHttpSource
import java.io.File

// Static target map. One entry per coherent on-disk artifact; fetch + import
// phases live in the same Target so the per-target mutex serializes them
// (you can't import a half-written campgrounds.geojson). Adding a new
// upstream = appending an entry. No admin-API or schema change.
//
// `repoRoot` is the working directory shell phases run in (so paths like
// `scripts/fetch_planet_fitness.py` resolve correctly).
fun defaultTargets(repoRoot: File): Map<String, Target> {
    val python = listOf("python3")

    fun script(name: String) = listOf(repoRoot.resolve("scripts/$name").absolutePath)

    val targets =
        listOf(
            // RFC 0004 step 3: ported in-process. The Python script lingers
            // as scripts/fetch_planet_fitness.py for offline debugging, but
            // the Tilt button + admin API now run the Kotlin path.
            Target(
                name = "planet-fitness",
                fetchPhases = listOf(Phase.Fetch.Kotlin("fetch:osm-pf", PlanetFitnessHttpSource())),
                importPhases = listOf(Phase.Import("import:osm-pf", "osm-pf")),
            ),
            Target(
                name = "state-parks",
                fetchPhases = listOf(Phase.Fetch.Kotlin("fetch:padus-state-parks", PadUsParksHttpSource.stateParks())),
                importPhases = listOf(Phase.Import("import:state-parks", "state-parks")),
            ),
            Target(
                name = "national-parks",
                fetchPhases =
                    listOf(Phase.Fetch.Kotlin("fetch:padus-national-parks", PadUsParksHttpSource.nationalParks())),
                importPhases = listOf(Phase.Import("import:national-parks", "national-parks")),
            ),
            // Composite. Four scripts share campgrounds.geojson; running them
            // under one target's mutex is the whole point.
            Target(
                name = "campgrounds",
                fetchPhases =
                    listOf(
                        Phase.Fetch.Shell("fetch_campgrounds.py", python + script("fetch_campgrounds.py")),
                        Phase.Fetch.Shell("fetch_bc_parks.py", python + script("fetch_bc_parks.py")),
                        Phase.Fetch.Shell("fetch_parks_canada.py", python + script("fetch_parks_canada.py")),
                        Phase.Fetch.Shell("enrich_campgrounds.py", python + script("enrich_campgrounds.py")),
                    ),
                importPhases = listOf(Phase.Import("import:uscampgrounds", "uscampgrounds")),
            ),
            // Curated JSON files committed to the repo — no fetch phase; the
            // importer reads data/parks-canada-*.json directly.
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
            // Tesla pricing cache rebuild. Fetch-only — there's no import
            // step (the cache files are served as-is from disk).
            Target(
                name = "tesla-pricing",
                fetchPhases =
                    listOf(
                        Phase.Fetch.Shell(
                            "make rebuild-superchargers",
                            listOf("make", "rebuild-superchargers"),
                        ),
                    ),
                importPhases = emptyList(),
            ),
        )
    return targets.associateBy { it.name }
}
