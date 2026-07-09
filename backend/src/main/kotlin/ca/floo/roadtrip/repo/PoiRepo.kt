package ca.floo.roadtrip.repo

import ca.floo.roadtrip.models.domain.Poi
import org.jooq.DSLContext

/**
 * Retired wide-POI importer.
 *
 * The canonical catalog no longer imports every source into one polymorphic
 * `pois` table. New ETLs write typed catalog rows (`campgrounds`,
 * `campsites`, `tesla_superchargers`, `planet_fitness_locations`) and then
 * create lean POI wrapper rows plus typed join rows.
 */
class Upsert(
    @Suppress("UNUSED_PARAMETER") private val ctx: DSLContext,
) {
    data class Result(
        val runId: Long,
        val seenCount: Int,
        val sweptCount: Int,
    )

    fun run(
        sources: Set<String>,
        pois: List<Poi>,
    ): Result {
        require(sources.isNotEmpty()) { "must specify at least one source for sweep scope" }
        throw UnsupportedOperationException(
            "wide POI upsert is retired; use canonical catalog ETL outputs and typed POI joins",
        )
    }
}

class UpsertException(
    message: String,
) : RuntimeException(message)
