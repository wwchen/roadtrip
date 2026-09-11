package ca.floo.roadtrip.service.geocode

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.model.api.GeocodeResponseDto
import ca.floo.roadtrip.model.api.GeocodeResultDto
import ca.floo.roadtrip.model.routing.GeocodeResult
import ca.floo.roadtrip.support.GeocodeException

private val lngLatRegex = Regex("""^-?\d{1,3}(\.\d{1,8})?,-?\d{1,3}(\.\d{1,8})?$""")
private const val MAX_QUERY_LENGTH = 200
private const val DEFAULT_GEOCODE_LIMIT = 5
private const val MIN_GEOCODE_LIMIT = 1
private const val MAX_GEOCODE_LIMIT = 10

private val geocodeLimitRange = MIN_GEOCODE_LIMIT..MAX_GEOCODE_LIMIT

/** What forward-geocoding came to. The route maps each arm to a status. */
internal sealed interface GeocodeOutcome {
    data class Found(
        val response: GeocodeResponseDto,
    ) : GeocodeOutcome

    /** No `roadtrip.mapbox.token`: geocoding is off, not failing. */
    data object NotConfigured : GeocodeOutcome

    data object BadQuery : GeocodeOutcome

    data object Unavailable : GeocodeOutcome
}

/**
 * Forward-geocoding as a use case: whether it is available at all, what a
 * usable query is, how many results the vendor may return, and which proximity
 * hints are well formed. The only place `client/mapbox`'s geocoder is used
 * outside DI.
 */
internal class GeocodeService(
    private val geocoder: MapboxGeocoder,
) {
    suspend fun geocode(
        query: String,
        autocomplete: Boolean,
        proximity: String?,
        limit: Int?,
    ): GeocodeOutcome {
        if (!geocoder.configured) return GeocodeOutcome.NotConfigured

        val trimmed = query.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_QUERY_LENGTH) return GeocodeOutcome.BadQuery

        val results =
            try {
                geocoder.forward(
                    trimmed,
                    autocomplete = autocomplete,
                    proximity = proximity?.takeIf { lngLatRegex.matches(it) },
                    limit = (limit ?: DEFAULT_GEOCODE_LIMIT).coerceIn(geocodeLimitRange),
                )
            } catch (e: GeocodeException) {
                return GeocodeOutcome.Unavailable
            }

        return GeocodeOutcome.Found(geocodeResponseDto(results))
    }
}

internal fun geocodeResponseDto(results: List<GeocodeResult>): GeocodeResponseDto =
    GeocodeResponseDto(
        results =
            results.map { result ->
                GeocodeResultDto(
                    id = result.id,
                    placeName = result.placeName,
                    placeType = result.placeType,
                    lng = result.lng,
                    lat = result.lat,
                    bbox = result.bbox?.let { listOf(it.west, it.south, it.east, it.north) },
                )
            },
    )
