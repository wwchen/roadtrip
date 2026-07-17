package ca.floo.roadtrip.model.api.poi

import kotlinx.serialization.Serializable

// /api/pois request body — bbox is required (4 numbers, [w,s,e,n]),
// zoom + categories optional. Corridor filtering lives behind
// POST /api/pois/on-route.
@Serializable
data class PoisRequestSchema(
    val bbox: List<Double>,
    val zoom: Int? = null,
    val categories: List<String>? = null,
)
