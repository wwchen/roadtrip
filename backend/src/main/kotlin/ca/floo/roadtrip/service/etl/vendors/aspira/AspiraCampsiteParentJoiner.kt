package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.models.domain.CampsiteParentLink
import ca.floo.roadtrip.service.etl.framework.CampsiteParentJoiner
import ca.floo.roadtrip.service.etl.framework.JoinerCtx

/**
 * Canonicalized Aspira campsite parent resolver.
 *
 * Preserves the old tenant-specific matching rules, but targets
 * `campsites`/`campgrounds` vendor refs instead of the removed
 * `reservables` table and retired POI-campsite link table.
 */
class AspiraCampsiteParentJoiner : CampsiteParentJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink> = ctx.repo.discoverAspiraLinks()

    private companion object {
        const val ADAPTER_NAME = "AspiraCampsiteParentJoiner"
    }
}
