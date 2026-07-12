package ca.floo.roadtrip.clients.aspira

import kotlinx.serialization.Serializable

/**
 * One response from Aspira's availability endpoint. `parkRollup` is the
 * `mapAvailabilities` array (one status per day, park-wide). `byMapLink` keys
 * each sub-area's daily-status array by its `childMapId` (string for JSON
 * compatibility — Aspira returns negative ints as object keys).
 */
@Serializable
data class AspiraAvailability(
    val mapId: Int,
    val parkRollup: List<Int>,
    val byMapLink: Map<String, List<Int>>,
    val byResource: Map<String, List<Int>> = emptyMap(),
)
