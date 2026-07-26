package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.support.firstHandlerFor
import org.slf4j.LoggerFactory

/**
 * Picks the [ClaimsDialect] for the configured provider slug.
 *
 * An unrecognized slug falls back to [StandardClaimsDialect] with a warning
 * rather than failing startup. A typo in `roadtrip.auth.provider` should
 * degrade upstream-identity fidelity, not take sign-in down — and the warning
 * makes the misconfiguration visible without an outage to find it.
 */
internal class ClaimsDialectRegistry(
    private val dialects: List<ClaimsDialect>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(dialects.any { it.id == StandardClaimsDialect.ID }) {
            "ClaimsDialectRegistry needs the ${StandardClaimsDialect.ID} dialect as its fallback"
        }
        val duplicates = dialects.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "duplicate claims dialect ids: $duplicates" }
    }

    fun forProvider(slug: String): ClaimsDialect {
        dialects.firstHandlerFor(ClaimsDialectId(slug))?.let { return it }
        log.warn(
            "no claims dialect for roadtrip.auth.provider='{}'; falling back to '{}'. " +
                "Upstream identity will not be recorded for these sign-ins.",
            slug,
            StandardClaimsDialect.ID,
        )
        return dialects.first { it.id == StandardClaimsDialect.ID }
    }

    companion object {
        /** Every dialect this build ships. */
        fun default(): ClaimsDialectRegistry =
            ClaimsDialectRegistry(
                listOf(
                    Auth0ClaimsDialect(),
                    WorkOsClaimsDialect(),
                    StandardClaimsDialect(),
                ),
            )
    }
}
