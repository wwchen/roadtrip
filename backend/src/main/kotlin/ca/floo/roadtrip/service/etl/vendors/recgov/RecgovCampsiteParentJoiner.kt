package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.models.domain.CampsiteParentLink
import ca.floo.roadtrip.service.etl.framework.CampsiteParentJoiner
import ca.floo.roadtrip.service.etl.framework.JoinerCtx

/**
 * Canonicalized rec.gov campsite parent resolver.
 *
 * The old adapter linked `reservables` to `pois`; with the canonical catalog it
 * resolves rec.gov campsite rows to campground rows through vendor refs.
 */
class RecgovCampsiteParentJoiner : CampsiteParentJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink> = ctx.repo.discoverRecgovLinks()

    private companion object {
        const val ADAPTER_NAME = "RecgovCampsiteParentJoiner"
    }
}
