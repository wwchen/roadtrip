package ca.floo.roadtrip.model.routing

import ca.floo.roadtrip.model.domain.poi.Bbox

data class GeocodeResult(
    val id: String,
    val placeName: String,
    val lng: Double,
    val lat: Double,
    val placeType: String,
    /**
     * The feature's extent, when the upstream reports one.
     *
     * A region — a state, a province, a country, a park — is an AREA, and a
     * single centre point inside it is an arbitrary choice the caller cannot
     * undo. Carrying the extent is what lets the map frame the thing the user
     * searched for rather than guess a zoom for it.
     *
     * Null for a feature the upstream gives no extent for (a street address,
     * a point of interest), which is the honest answer: there is nothing to
     * frame and the centre is the whole story.
     */
    val bbox: Bbox? = null,
)
