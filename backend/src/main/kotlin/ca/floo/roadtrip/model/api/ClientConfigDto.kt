package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Public runtime configuration for the browser.
 *
 * The CARTO basemaps key is still stored as a runtime secret so it is not baked
 * into CI-built assets, but tile providers necessarily receive it from the
 * browser once the basemap is requested. Restrict it at the provider by Referer.
 */
@Serializable
data class ClientConfigDto(
    @SerialName("carto_basemaps_api_key") val cartoBasemapsApiKey: String? = null,
)
