package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.models.domain.CampsiteParentLink
import ca.floo.roadtrip.service.etl.framework.CampsiteParentJoiner
import ca.floo.roadtrip.service.etl.framework.JoinerCtx

/**
 * Canonicalized ReserveCalifornia campsite parent resolver.
 */
class ReserveCaliforniaCampsiteParentJoiner : CampsiteParentJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink> = ctx.repo.discoverReserveCaliforniaLinks()

    private companion object {
        const val ADAPTER_NAME = "ReserveCaliforniaCampsiteParentJoiner"
    }
}
