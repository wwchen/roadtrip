package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.models.domain.CampsiteParentLink
import ca.floo.roadtrip.service.etl.framework.CampsiteParentJoiner
import ca.floo.roadtrip.service.etl.framework.JoinerCtx

/**
 * Canonicalized ReserveAmerica campsite parent resolver.
 *
 * Matches per-site vendor refs to parent campground vendor refs by
 * `(contract_code, park_id)`, using the same tenant keys as the availability
 * adapter.
 */
class ReserveAmericaCampsiteParentJoiner : CampsiteParentJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink> = ctx.repo.discoverReserveAmericaLinks()

    private companion object {
        const val ADAPTER_NAME = "ReserveAmericaCampsiteParentJoiner"
    }
}
