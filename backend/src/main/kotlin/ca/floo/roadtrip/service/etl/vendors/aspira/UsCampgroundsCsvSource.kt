package ca.floo.roadtrip.service.etl.vendors.aspira

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** uscampgrounds.info — the payload is a CSV string. State is column 12. */
class UsCampgroundsCsvSource(
    private val envelopes: List<ca.floo.roadtrip.model.metadata.Envelope>,
) : GeometrySource {
    override fun indexInto(byName: MutableMap<String, Pair<Double, Double>>) {
        for (env in envelopes) {
            val text = env.payload.jsonPrimitive.contentOrNull ?: continue
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val cols = csvSplit(line)
                if (cols.size < 13) continue
                val lon = cols[0].toDoubleOrNull() ?: continue
                val lat = cols[1].toDoubleOrNull() ?: continue
                val name = cols.getOrNull(4)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val key = normalize(name)
                if (key.isNotEmpty()) byName.putIfAbsent(key, lat to lon)
            }
        }
    }
}
