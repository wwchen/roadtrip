package ca.floo.roadtrip.importer

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.parameters
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// In-process port of scripts/fetch_parks.py.
//
// Pulls from the USGS PAD-US ArcGIS FeatureServer. ArcGIS REST has a per-query
// record cap (usually 2000), so we paginate via resultOffset. Geometry is
// requested simplified (maxAllowableOffset=0.001°, ≈111m) — parks at
// continental-US zoom don't need cm precision.
//
// One instance per layer (state vs national) so the IngestController can
// trigger them independently — the targets are independent in defaultTargets.
// Both share the same SVC, FIELDS, and pagination logic.
class PadUsParksHttpSource(
    override val name: String,
    private val whereClause: String,
    private val outputName: String,
    private val sleepMs: Long = 200,
) : HttpFetchSource {
    override suspend fun fetch(
        client: HttpClient,
        dataDir: File,
    ): FetchOutcome {
        val log = LoggerFactory.getLogger(javaClass)
        log.info("[{}] querying PAD-US ({})…", name, whereClause)
        val features = queryAll(client, whereClause)

        val out = File(dataDir, outputName)
        val fetchedAt = ISO_UTC.format(Instant.now().atOffset(ZoneOffset.UTC))
        val collection =
            buildJsonObject {
                put("_fetched_at", fetchedAt)
                put("type", "FeatureCollection")
                put("features", buildJsonArray { features.forEach { add(it) } })
            }
        out.parentFile.mkdirs()
        out.writeText(collection.toString())
        log.info("[{}] wrote {} features to {}", name, features.size, out.path)

        return FetchOutcome(featureCount = features.size, outputFile = out)
    }

    private suspend fun queryAll(
        client: HttpClient,
        where: String,
        page: Int = 1000,
    ): List<JsonElement> {
        val log = LoggerFactory.getLogger(javaClass)
        val all = mutableListOf<JsonElement>()
        var offset = 0
        while (true) {
            val url =
                URLBuilder("$SVC/query")
                    .apply {
                        parameters.append("where", where)
                        parameters.append("outFields", FIELDS)
                        parameters.append("f", "geojson")
                        parameters.append("returnGeometry", "true")
                        parameters.append("geometryPrecision", "5")
                        parameters.append("maxAllowableOffset", SIMPLIFY)
                        parameters.append("outSR", "4326")
                        parameters.append("resultOffset", offset.toString())
                        parameters.append("resultRecordCount", page.toString())
                    }.build()
            log.info("[{}]   fetch offset={}", name, offset)
            val resp = client.get(url)
            if (resp.status != HttpStatusCode.OK) {
                error("PAD-US query failed: HTTP ${resp.status}")
            }
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val feats = (body["features"] as? JsonArray)?.toList() ?: emptyList()
            if (feats.isEmpty()) break
            all.addAll(feats)
            if (feats.size < page) break
            offset += page
            delay(sleepMs)
        }
        return all
    }

    companion object {
        private const val SVC =
            "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/Manager_Name_PADUS/FeatureServer/0"
        private const val FIELDS = "Unit_Nm,Loc_Nm,State_Nm,Mang_Name,Des_Tp,GIS_Acres"

        // 0.001° ≈ 111m at the equator. Drop to 0.0005 if outlines look too
        // blocky; tested at continental zoom and the simplification is invisible.
        private const val SIMPLIFY = "0.001"

        private val ISO_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

        // Convenience factories matching scripts/fetch_parks.py's two layers.
        fun nationalParks() =
            PadUsParksHttpSource(
                name = "padus-national-parks",
                whereClause = "Des_Tp='NP'",
                outputName = "national-parks.geojson",
            )

        fun stateParks() =
            PadUsParksHttpSource(
                name = "padus-state-parks",
                whereClause = "Des_Tp='SP' AND Mang_Type='STAT'",
                outputName = "state-parks.geojson",
            )
    }
}
