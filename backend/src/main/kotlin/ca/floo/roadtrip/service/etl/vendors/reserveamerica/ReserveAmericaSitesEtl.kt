package ca.floo.roadtrip.service.etl.vendors.reserveamerica

import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaCatalogParser
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampsiteEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Campsite catalog terminal (RFC 0008) for one ReserveAmerica tenant.
 * Reads the `campsite-<parkId>-<startIdx>` HTML envelopes captured by
 * `scripts/fetch_reserveamerica_campsites.py` and emits one campsite per
 * site.
 *
 * `vendor` is per-tenant (`reserveamerica_ny`) — matching the availability
 * adapter's provider key; `vendor_id` is the scraped `siteId`, so catalog rows bind
 * to availability by construction. `loop`/`site_type` are null: the calendar's
 * loopName is a pagination bucket, not a real loop.
 */
class ReserveAmericaSitesEtl(
    override val etlSlug: String,
    private val contractCode: String,
) : CampsiteEtl<ReserveAmericaSitesEtl.Parsed> {
    override val multiPart: Boolean = true

    data class Parsed(
        val sites: List<ReserveAmericaCatalogParser.CatalogSite>,
    )

    override fun parse(inputs: InputBundle): Sequence<ParseResult<Parsed>> =
        sequence {
            val parsed =
                Parsed(
                    inputs
                        .soleEnvelopes()
                        .flatMap { ReserveAmericaCatalogParser.parse(it.payload.jsonPrimitive.content) },
                )
            if (parsed.sites.isEmpty()) {
                yield(ParseResult.Bad(null, listOf("$etlSlug: no ReserveAmerica campsite rows parsed")))
            } else {
                yield(ParseResult.Ok(parsed))
            }
        }

    override fun transform(
        dto: Parsed,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampsiteUpsertCandidate>> =
        dto.sites
            .distinctBy { it.siteId }
            .asSequence()
            .map { site ->
                TransformResult.Ok(
                    CampsiteUpsertCandidate(
                        dataProviderRef = DataProviderRef.ReserveAmerica(id = site.siteId),
                        bookingProvider = BookingProvider.RESERVEAMERICA,
                        bookingProviderRef = site.siteId,
                        parentDataProviderRef =
                            parentCampgroundVendor(contractCode)?.let {
                                DataProviderRef.ReserveAmerica(id = "$PARENT_CAMPGROUND_REF_PREFIX${site.parkId}")
                            },
                        name = site.name,
                        sourcePayload =
                            buildJsonObject {
                                put("site_id", site.siteId)
                                put("name", site.name)
                                put(PARENT_CONTRACT_KEY, contractCode)
                                put(PARENT_PARK_KEY, site.parkId)
                            },
                    ),
                )
            }

    companion object {
        const val PARENT_CONTRACT_KEY = "_parent_contract_code"
        const val PARENT_PARK_KEY = "_parent_park_id"
        const val PARENT_CAMPGROUND_REF_PREFIX = "ra-"

        fun parentCampgroundVendor(contractCode: String): DataProvider? =
            when (contractCode.uppercase()) {
                "ABPP", "NY" -> DataProvider.RESERVEAMERICA
                else -> null
            }
    }
}
