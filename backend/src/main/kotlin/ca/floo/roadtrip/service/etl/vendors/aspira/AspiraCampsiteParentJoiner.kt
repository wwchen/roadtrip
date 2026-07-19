package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.domain.AspiraCampgroundParentCandidate
import ca.floo.roadtrip.model.domain.AspiraCampsiteParentCandidate
import ca.floo.roadtrip.model.domain.CampsiteParentLink
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

    override fun discoverLinks(ctx: JoinerCtx): List<CampsiteParentLink> {
        val campgrounds = ctx.campsiteParentJoinerRepo.fetchAspiraCampgroundParentCandidates()
        val campgroundsByExternalId =
            campgrounds.groupBy { campground ->
                AspiraExternalParentKey(
                    vendor = campground.vendor,
                    externalId = campground.externalId,
                )
            }
        val campgroundsByResourceLocationId =
            campgrounds
                .filter { campground -> campground.resourceLocationId.isUseful() }
                .groupBy { campground ->
                    AspiraResourceLocationParentKey(
                        vendor = campground.vendor,
                        resourceLocationId = campground.resourceLocationId!!,
                    )
                }

        val links = LinkedHashSet<CampsiteParentLink>()
        for (site in ctx.campsiteParentJoinerRepo.fetchAspiraCampsiteParentCandidates()) {
            val parentVendor = parentVendorBySiteVendor[site.vendor] ?: continue
            site.parentExternalId()?.let { parentExternalId ->
                campgroundsByExternalId[AspiraExternalParentKey(parentVendor, parentExternalId)]
                    .orEmpty()
                    .addLinks(site, links)
            }
            site.parentResourceLocationId()?.let { parentResourceLocationId ->
                campgroundsByResourceLocationId[
                    AspiraResourceLocationParentKey(parentVendor, parentResourceLocationId),
                ].orEmpty().addLinks(site, links)
            }
        }
        return links.toList()
    }

    private companion object {
        const val ADAPTER_NAME = "AspiraCampsiteParentJoiner"

        private const val ASPIRA_PARENT_REF_PREFIX = "aspira-"
        private const val ASPIRA_PARENT_REF_SEPARATOR = "-"
        private const val ASPIRA_VENDOR = "aspira"

        private val parentVendorBySiteVendor =
            mapOf(
                ASPIRA_VENDOR to ASPIRA_VENDOR,
            )

        private fun AspiraCampsiteParentCandidate.parentExternalId(): String? {
            val transactionLocationId = transactionLocationId.usefulOrNull() ?: return null
            val mapId = mapId.usefulOrNull() ?: return null
            return "$ASPIRA_PARENT_REF_PREFIX$transactionLocationId$ASPIRA_PARENT_REF_SEPARATOR$mapId"
        }

        private fun AspiraCampsiteParentCandidate.parentResourceLocationId(): String? =
            vendorRefResourceLocationId.usefulOrNull() ?: sourceParentResourceLocationId.usefulOrNull()

        private fun List<AspiraCampgroundParentCandidate>.addLinks(
            site: AspiraCampsiteParentCandidate,
            links: MutableSet<CampsiteParentLink>,
        ) {
            for (campground in this) {
                links += CampsiteParentLink(campsiteId = site.campsiteId, campgroundId = campground.campgroundId)
            }
        }

        private fun String?.isUseful(): Boolean = usefulOrNull() != null

        private fun String?.usefulOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private data class AspiraExternalParentKey(
    val vendor: String,
    val externalId: String,
)

private data class AspiraResourceLocationParentKey(
    val vendor: String,
    val resourceLocationId: String,
)
