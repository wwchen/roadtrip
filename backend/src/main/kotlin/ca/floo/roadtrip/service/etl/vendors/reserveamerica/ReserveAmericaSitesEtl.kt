package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaCatalogParser
import ca.floo.roadtrip.models.metadata.ValidationResult
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlOutput
import ca.floo.roadtrip.service.etl.framework.CampsiteEtlRecord
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.SourceEtl
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Campsite catalog terminal (RFC 0008) for one ReserveAmerica tenant.
 * Reads the `campsite-<parkId>-<startIdx>` HTML envelopes captured by
 * `scripts/fetch_reserveamerica_campsites.py` and emits one campsite per
 * site. The [ReserveAmericaCampsiteParentJoiner] links them to POIs.
 *
 * `vendor` is per-tenant (`reserveamerica_ny`) — matching the availability
 * adapter's provider key; `vendor_id` is the scraped `siteId`, so catalog rows bind
 * to availability by construction. `loop`/`site_type` are null: the calendar's
 * loopName is a pagination bucket, not a real loop.
 */
class ReserveAmericaSitesEtl(
    override val etlSlug: String,
    private val contractCode: String,
) : SourceEtl<ReserveAmericaSitesEtl.Parsed, CampsiteEtlOutput> {
    override val multiPart: Boolean = true

    data class Parsed(
        val sites: List<ReserveAmericaCatalogParser.CatalogSite>,
    )

    override fun parse(inputs: InputBundle): Parsed =
        Parsed(
            inputs
                .soleEnvelopes()
                .flatMap { ReserveAmericaCatalogParser.parse(it.payload.jsonPrimitive.content) },
        )

    override fun validate(dto: Parsed): ValidationResult<Parsed> =
        if (dto.sites.isEmpty()) {
            ValidationResult.Bad(null, listOf("$etlSlug: no ReserveAmerica campsite rows parsed"))
        } else {
            ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: Parsed,
        ctx: TransformCtx,
    ): CampsiteEtlOutput {
        val vendor = "reserveamerica_${contractCode.lowercase()}"
        val campsites =
            dto.sites
                .distinctBy { it.siteId }
                .map { site ->
                    CampsiteEtlRecord(
                        vendor = vendor,
                        vendorRefId = site.siteId,
                        parentVendor = parentCampgroundVendor(contractCode),
                        parentVendorRefId = "$PARENT_CAMPGROUND_REF_PREFIX${site.parkId}",
                        name = site.name,
                        sourcePayload =
                            buildJsonObject {
                                put("site_id", site.siteId)
                                put("name", site.name)
                                put(PARENT_CONTRACT_KEY, contractCode)
                                put(PARENT_PARK_KEY, site.parkId)
                            },
                        vendorRefPayload =
                            buildJsonObject {
                                put("site_id", site.siteId)
                                put(PARENT_CONTRACT_KEY, contractCode)
                                put(PARENT_PARK_KEY, site.parkId)
                            },
                    )
                }
        return CampsiteEtlOutput(campsites = campsites)
    }

    companion object {
        const val PARENT_CONTRACT_KEY = "_parent_contract_code"
        const val PARENT_PARK_KEY = "_parent_park_id"
        const val PARENT_CAMPGROUND_REF_PREFIX = "ra-"

        fun parentCampgroundVendor(contractCode: String): String? =
            when (contractCode.uppercase()) {
                "ABPP" -> "alberta-provincial"
                "NY" -> "new-york-state-parks"
                else -> null
            }
    }
}
