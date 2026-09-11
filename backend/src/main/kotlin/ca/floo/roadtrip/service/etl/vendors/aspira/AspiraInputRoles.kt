package ca.floo.roadtrip.service.etl.vendors.aspira

/**
 * Substring markers that name an Aspira tenant's non-geometry inputs. A run's
 * inputs partition into the declared geometry sources and these role feeds, so
 * a role is only ever looked for among the slugs geometry did not claim.
 */
internal object AspiraInputRoles {
    const val MAPS = "maps"
    const val INVENTORY = "inventory"
    const val DICTIONARIES = "dictionaries"
}
