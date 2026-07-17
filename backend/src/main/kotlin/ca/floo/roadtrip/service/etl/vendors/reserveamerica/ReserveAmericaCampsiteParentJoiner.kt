package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.model.domain.CampsiteParentLink
import ca.floo.roadtrip.model.domain.ReserveAmericaCampgroundParentCandidate
import ca.floo.roadtrip.model.domain.ReserveAmericaCampsiteParentCandidate
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

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink> {
        val campgroundsByParentKey =
            ctx.repo
                .fetchReserveAmericaCampgroundParentCandidates()
                .mapNotNull(::parentKeyForCampground)
                .groupBy({ it.key }, { it.campgroundId })

        val links = LinkedHashSet<CampsiteParentLink>()
        for (site in ctx.repo.fetchReserveAmericaCampsiteParentCandidates()) {
            val key = parentKeyForSite(site) ?: continue
            for (campgroundId in campgroundsByParentKey[key].orEmpty()) {
                links += CampsiteParentLink(campsiteId = site.campsiteId, campgroundId = campgroundId)
            }
        }
        return links.toList()
    }

    private companion object {
        const val ADAPTER_NAME = "ReserveAmericaCampsiteParentJoiner"

        private const val PARENT_REF_PREFIX = "ra-"

        private fun parentKeyForSite(site: ReserveAmericaCampsiteParentCandidate): ReserveAmericaParentKey? {
            val contractCode =
                site.vendorRefParentContractCode.usefulOrNull()
                    ?: site.sourceParentContractCode.usefulOrNull()
                    ?: return null
            val parkId =
                site.vendorRefParentParkId.usefulOrNull()
                    ?: site.sourceParentParkId.usefulOrNull()
                    ?: return null
            return ReserveAmericaParentKey(
                externalId = "$PARENT_REF_PREFIX$parkId",
                contractCode = contractCode,
            )
        }

        private fun parentKeyForCampground(campground: ReserveAmericaCampgroundParentCandidate): ReserveAmericaCampgroundParentKey? {
            val contractCode = campground.contractCode.usefulOrNull() ?: return null
            return ReserveAmericaCampgroundParentKey(
                campgroundId = campground.campgroundId,
                key =
                    ReserveAmericaParentKey(
                        externalId = campground.externalId,
                        contractCode = contractCode,
                    ),
            )
        }

        private fun String?.usefulOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private data class ReserveAmericaParentKey(
    val externalId: String,
    val contractCode: String,
)

private data class ReserveAmericaCampgroundParentKey(
    val campgroundId: Long,
    val key: ReserveAmericaParentKey,
)
