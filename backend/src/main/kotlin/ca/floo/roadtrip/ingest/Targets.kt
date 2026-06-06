package ca.floo.roadtrip.ingest

import java.io.File

// Static target map. Adding a new target = appending an entry. No admin-API
// change, no schema change. Each target's phase list is the exact sequence
// today's `make` targets run; the wrapper just makes them observable.
//
// `repoRoot` is the working directory shell phases run in (so paths like
// `scripts/fetch_planet_fitness.py` resolve correctly).
fun defaultTargets(repoRoot: File): Map<String, Target> {
    val python = listOf("python3")

    fun script(name: String) = listOf(repoRoot.resolve("scripts/$name").absolutePath)

    val targets =
        listOf(
            Target(
                name = "planet-fitness",
                phases =
                    listOf(
                        Phase.Shell("fetch_planet_fitness.py", python + script("fetch_planet_fitness.py")),
                        Phase.Kotlin("import:osm-pf", "osm-pf"),
                    ),
            ),
            Target(
                name = "state-parks",
                phases =
                    listOf(
                        Phase.Shell("fetch_parks.py state", python + script("fetch_parks.py") + listOf("--layer", "state-parks")),
                        Phase.Kotlin("import:state-parks", "state-parks"),
                    ),
            ),
            Target(
                name = "national-parks",
                phases =
                    listOf(
                        Phase.Shell(
                            "fetch_parks.py national",
                            python + script("fetch_parks.py") + listOf("--layer", "national-parks"),
                        ),
                        Phase.Kotlin("import:national-parks", "national-parks"),
                    ),
            ),
            // Composite. Four scripts share campgrounds.geojson; running them
            // under one target's mutex is the whole point.
            Target(
                name = "campgrounds",
                phases =
                    listOf(
                        Phase.Shell("fetch_campgrounds.py", python + script("fetch_campgrounds.py")),
                        Phase.Shell("fetch_bc_parks.py", python + script("fetch_bc_parks.py")),
                        Phase.Shell("fetch_parks_canada.py", python + script("fetch_parks_canada.py")),
                        Phase.Shell("enrich_campgrounds.py", python + script("enrich_campgrounds.py")),
                        Phase.Kotlin("import:uscampgrounds", "uscampgrounds"),
                    ),
            ),
            // Curated JSON files — no fetch phase; importer reads the committed
            // data/parks-canada-*.json directly.
            Target(
                name = "parks-canada-curated",
                phases = listOf(Phase.Kotlin("import:parks-canada", "parks-canada")),
            ),
            Target(
                name = "alberta-provincial",
                phases = listOf(Phase.Kotlin("import:alberta-provincial", "alberta-provincial")),
            ),
            // Tesla pricing cache rebuild. Cache-only (no upstream network);
            // full refresh stays in `make refresh-superchargers` because the
            // egress IP is bound to the Tesla cookies in .env.
            Target(
                name = "tesla-pricing",
                phases =
                    listOf(
                        Phase.Shell(
                            "make rebuild-superchargers",
                            listOf("make", "rebuild-superchargers"),
                        ),
                    ),
            ),
        )
    return targets.associateBy { it.name }
}
