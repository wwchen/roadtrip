package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.RefLinkRepo
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.BookingIdentityResolver
import ca.floo.roadtrip.service.availability.CampsiteCatalogService
import ca.floo.roadtrip.service.ref.DbRefResolver
import org.jooq.DSLContext

/** The catalog service as it ships: the shipped registry names the booking site. */
internal fun testCampsiteCatalogService(
    ctx: DSLContext,
    campsitesRepo: CampsiteRepo,
    targets: AvailabilityTargetResolver,
): CampsiteCatalogService =
    CampsiteCatalogService(
        refResolver = DbRefResolver(RefLinkRepo(ctx)),
        campsitesRepo = campsitesRepo,
        campgroundRepo = CampgroundRepo(ctx),
        targets = targets,
        identities = BookingIdentityResolver(shippedTenantRegistry()),
    )
