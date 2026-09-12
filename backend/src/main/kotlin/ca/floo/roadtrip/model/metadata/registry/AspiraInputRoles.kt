package ca.floo.roadtrip.model.metadata.registry

/**
 * Substring markers that name an Aspira tenant's non-geometry inputs. A run's
 * inputs partition into the declared geometry sources and these role feeds, so
 * a role is only ever looked for among the slugs geometry did not claim — and
 * the boot validator holds the row to that partition.
 */
internal object AspiraInputRoles {
    const val MAPS = "maps"
    const val INVENTORY = "inventory"
    const val DICTIONARIES = "dictionaries"

    /** No geometry source may carry any of these; each names a sibling role feed. */
    val all = listOf(MAPS, INVENTORY, DICTIONARIES)

    /** The roles a geometry-joining row must supply exactly once. Dictionaries stay optional. */
    val required = listOf(MAPS, INVENTORY)
}
