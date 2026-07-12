package ca.floo.roadtrip.service.etl.vendors.reservecalifornia

import ca.floo.roadtrip.models.domain.CampsiteParentLink
import ca.floo.roadtrip.models.domain.ReserveCaliforniaCampsiteParentCandidate
import ca.floo.roadtrip.service.etl.framework.CampsiteParentJoiner
import ca.floo.roadtrip.service.etl.framework.JoinerCtx

/**
 * Canonicalized ReserveCalifornia campsite parent resolver.
 */
class ReserveCaliforniaCampsiteParentJoiner : CampsiteParentJoiner {
    override val adapter: String = ADAPTER_NAME

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink> {
        val campgroundIdsByExternalId =
            ctx.repo
                .fetchReserveCaliforniaCampgroundParentCandidates()
                .groupBy({ it.externalId }, { it.campgroundId })

        val links = LinkedHashSet<CampsiteParentLink>()
        for (site in ctx.repo.fetchReserveCaliforniaCampsiteParentCandidates()) {
            val parentExternalId = site.parentExternalId() ?: continue
            for (campgroundId in campgroundIdsByExternalId[parentExternalId].orEmpty()) {
                links += CampsiteParentLink(campsiteId = site.campsiteId, campgroundId = campgroundId)
            }
        }
        return links.toList()
    }

    private companion object {
        const val ADAPTER_NAME = "ReserveCaliforniaCampsiteParentJoiner"

        private const val PARENT_REF_PREFIX = "rc-"

        private fun ReserveCaliforniaCampsiteParentCandidate.parentExternalId(): String? {
            val placeId = vendorRefPlaceId.usefulOrNull() ?: sourceParentPlaceId.usefulOrNull() ?: return null
            return "$PARENT_REF_PREFIX$placeId"
        }

        private fun String?.usefulOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
