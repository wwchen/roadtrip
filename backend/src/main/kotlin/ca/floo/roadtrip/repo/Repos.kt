package ca.floo.roadtrip.repo

import org.jooq.DSLContext

/**
 * One connection context's repo handles, built on first use. Lists only the
 * repos a transactional or ETL path needs today; a new member is one lazy line.
 *
 * Constructed only by [JooqUnitOfWork], so a caller cannot bind a bundle to a
 * context of its own choosing.
 *
 * The handles are named for what they hold, not for their type: inside a block
 * the reader is already in repo-land, and `repos.campgroundRepo` would say it
 * twice.
 */
@Suppress("TypedInfrastructurePropertyNaming")
class Repos internal constructor(
    private val txn: DSLContext,
) {
    val watches: AvailabilityWatchRepo by lazy { AvailabilityWatchRepo(txn) }
    val pollers: AvailabilityPollerRepo by lazy { AvailabilityPollerRepo(txn) }
    val users: UserRepo by lazy { UserRepo(txn) }
    val userIdentities: UserIdentityRepo by lazy { UserIdentityRepo(txn) }
    val campgrounds: CampgroundRepo by lazy { CampgroundRepo(txn) }
    val campsites: CampsiteRepo by lazy { CampsiteRepo(txn) }
    val teslaSuperchargers: TeslaSuperchargerRepo by lazy { TeslaSuperchargerRepo(txn) }
    val planetFitnessLocations: PlanetFitnessLocationRepo by lazy { PlanetFitnessLocationRepo(txn) }
    val importRuns: ImportRunRepo by lazy { ImportRunRepo(txn) }
    val ingestRuns: IngestRunRepo by lazy { IngestRunRepo(txn) }
}
