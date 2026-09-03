package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val LONGITUDE_COL = 0
private const val LATITUDE_COL = 1
private const val NAME_COL = 4
private const val STATE_COL = 12
private const val MIN_COLS = STATE_COL + 1

/**
 * uscampgrounds.info — the payload is a CSV string.
 *
 * The file is nationwide, so [stateFilter] is what keeps a single-state tenant
 * from matching a same-named campground elsewhere: names repeat across states,
 * and the index keeps the first row it sees, so an unfiltered Washington leaf
 * could take South Dakota's coordinates. Null means index every state, which is
 * what the non-US tenants want.
 */
class UsCampgroundsCsvSource(
    private val envelopes: List<ca.floo.roadtrip.model.metadata.Envelope>,
    private val stateFilter: String? = null,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        val wantedState = stateFilter?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        for (env in envelopes) {
            val text = env.payload.jsonPrimitive.contentOrNull ?: continue
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val cols = csvSplit(line)
                if (cols.size < MIN_COLS) continue
                if (wantedState != null && cols[STATE_COL].trim().uppercase() != wantedState) continue
                val lon = cols[LONGITUDE_COL].toDoubleOrNull() ?: continue
                val lat = cols[LATITUDE_COL].toDoubleOrNull() ?: continue
                val name = cols.getOrNull(NAME_COL)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}
