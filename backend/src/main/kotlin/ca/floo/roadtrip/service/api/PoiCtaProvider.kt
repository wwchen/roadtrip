package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.models.api.PoiCtaSchema
import ca.floo.roadtrip.models.domain.ProviderRef

private const val RESERVE_CTA_KIND = "reserve"

internal interface PoiCtaProvider {
    fun bookingSystem(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): String? = null

    fun reserveCta(
        providerRef: ProviderRef?,
        infoUrl: String?,
    ): PoiCtaSchema? = null
}

internal fun reserveCta(
    url: String,
    label: String,
): PoiCtaSchema =
    PoiCtaSchema(
        url = url,
        label = label,
        kind = RESERVE_CTA_KIND,
    )
