package ca.floo.roadtrip.service.etl.vendors.recgov

import ca.floo.roadtrip.model.domain.CampsiteAttribute
import ca.floo.roadtrip.model.domain.CampsiteUpsertCandidate
import ca.floo.roadtrip.model.domain.DEFAULT_CAMPSITE_KIND
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import ca.floo.roadtrip.model.metadata.TransformResult
import ca.floo.roadtrip.service.etl.framework.CampsiteEtl
import ca.floo.roadtrip.service.etl.framework.InputBundle
import ca.floo.roadtrip.service.etl.framework.TransformCtx
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val FIRE_PIT_ATTRIBUTE = "fire pit"
private const val PICNIC_TABLE_ATTRIBUTE = "picnic table"
private val adaAccessibleAttributes = setOf("accessible", "ada accessible")
private val maxCarsAttributes = setOf("max num of vehicles", "max vehicles")
private const val DRIVEWAY_LENGTH_ATTRIBUTE = "driveway length"
private const val MAX_VEHICLE_LENGTH_ATTRIBUTE = "max vehicle length"

private val yesValues = setOf("yes", "y", "true")
private val noValues = setOf("no", "n", "false")
private val leadingIntRegex = Regex("^\\d+")

private const val RESERVE_TYPE_LABEL = "Reserve type"
private const val TYPE_OF_USE_LABEL = "Type of use"

/**
 * Terminal ETL for the `campsite_data` section. Reads per-facility
 * envelopes captured by `scripts/fetch_recgov_campsites.py` and emits one
 * campsite per campsite.
 *
 * Parent linking is explicit on every emitted row: the parent data ref is the
 * Rec.gov facility ID that CampsiteRepo resolves to the campground row.
 *
 * The full upstream campsite blob is preserved verbatim in `sourcePayload`
 * for forensic queries — RFC 0008's data trust principle.
 *
 * Multi-part input: the fetcher writes one file per facility under
 * `data/raw/recgov-campsites/<ts>/facility-<id>.json`. We get all of
 * them via `inputs.soleEnvelopes()` and emit one campsite per
 * `campsites` map entry.
 */
class RecGovCampsitesEtl(
    override val etlSlug: String,
) : CampsiteEtl<List<Envelope>> {
    override val multiPart: Boolean = true

    override fun parse(inputs: InputBundle): Sequence<ParseResult<List<Envelope>>> =
        sequence {
            val envelopes = inputs.soleEnvelopes()
            require(envelopes.isNotEmpty()) {
                "$etlSlug: no envelopes captured (run fetch_recgov_campsites.py first)"
            }
            yield(ParseResult.Ok(envelopes))
        }

    override fun transform(
        dto: List<Envelope>,
        ctx: TransformCtx,
    ): Sequence<TransformResult<CampsiteUpsertCandidate>> =
        sequence {
            for (envelope in dto) {
                // FacilityID lives in the captured request URL path. The
                // upstream campsite blob doesn't carry it, but parent resolution
                // needs it to link each site to its campground row.
                val sourceId = envelope.part ?: envelope.request.url
                val facilityId = parseFacilityIdFromUrl(envelope.request.url)
                if (facilityId == null) {
                    yield(TransformResult.Bad(sourceId, listOf("missing facility id in request URL")))
                    continue
                }
                val payload = envelope.payload as? JsonObject
                if (payload == null) {
                    yield(TransformResult.Bad(sourceId, listOf("expected JSON object payload")))
                    continue
                }
                val rawCampsites = payload["campsites"] as? JsonObject
                if (rawCampsites == null) {
                    yield(TransformResult.Bad(sourceId, listOf("missing campsites object")))
                    continue
                }
                for ((campsiteId, element) in rawCampsites) {
                    val raw = element as? JsonObject
                    if (raw == null) {
                        yield(TransformResult.Bad(campsiteId, listOf("expected campsite JSON object")))
                        continue
                    }
                    val promoted = promoteAttributes(raw)
                    val siteName = raw.stringField("site") ?: campsiteId
                    val campsiteType = raw.stringField("campsite_type")
                    yield(
                        TransformResult.Ok(
                            CampsiteUpsertCandidate(
                                dataProviderRef = DataProviderRef.RecGov(id = campsiteId),
                                bookingProvider = BookingProvider.RECGOV,
                                bookingProviderRef = campsiteId,
                                parentDataProviderRef = DataProviderRef.RecGov(id = facilityId),
                                name = siteName,
                                loopName = raw.stringField("loop"),
                                kind = campsiteType ?: DEFAULT_CAMPSITE_KIND,
                                kindListed = campsiteType,
                                equipment = equipmentNames(raw),
                                firepit = promoted.firepit,
                                picnicTable = promoted.picnicTable,
                                adaAccessible = promoted.adaAccessible,
                                maxPeople = raw["max_num_people"]?.jsonPrimitive?.intOrNull,
                                maxCars = promoted.maxCars,
                                drivewayLength = promoted.drivewayLength,
                                maxRvLength = promoted.maxRvLength,
                                attributes = promoted.attributes,
                                minPeople = raw["min_num_people"]?.jsonPrimitive?.intOrNull,
                                sourcePayload =
                                    withSynthetic(
                                        raw,
                                        mapOf("_parent_facility_id" to JsonPrimitive(facilityId)),
                                    ),
                            ),
                        ),
                    )
                }
            }
        }

    private fun equipmentNames(raw: JsonObject): List<String> =
        (raw["equipment_types"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            ?: emptyList()

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Rec.gov ships every campsite fact as a free-form (name, value) pair. The
     * handful the drawer renders as typed columns are promoted here; the rest
     * stay as named attributes.
     */
    private fun promoteAttributes(raw: JsonObject): PromotedAttributes {
        var firepit: Boolean? = null
        var picnicTable: Boolean? = null
        var adaAccessible: Boolean? = null
        var maxCars: Int? = null
        var drivewayLength: Int? = null
        var maxRvLength: Int? = null
        val rest = mutableListOf<CampsiteAttribute>()

        for (element in (raw["attributes"] as? JsonArray).orEmpty()) {
            val attribute = element as? JsonObject ?: continue
            val name = attribute.stringField("attribute_name") ?: continue
            val value = attribute.stringField("attribute_value")
            // A promoted name whose value the column can't hold ("Fire Pit: Seasonal")
            // stays an attribute rather than becoming nothing at all.
            val promoted =
                when (name.lowercase()) {
                    FIRE_PIT_ATTRIBUTE -> booleanValue(value)?.also { firepit = it }
                    PICNIC_TABLE_ATTRIBUTE -> booleanValue(value)?.also { picnicTable = it }
                    in adaAccessibleAttributes -> booleanValue(value)?.also { adaAccessible = it }
                    in maxCarsAttributes -> leadingInt(value)?.also { maxCars = it }
                    DRIVEWAY_LENGTH_ATTRIBUTE -> leadingInt(value)?.also { drivewayLength = it }
                    MAX_VEHICLE_LENGTH_ATTRIBUTE -> leadingInt(value)?.also { maxRvLength = it }
                    else -> null
                }
            if (promoted == null) rest += CampsiteAttribute(name, value)
        }
        raw.stringField("campsite_reserve_type")?.let { rest += CampsiteAttribute(RESERVE_TYPE_LABEL, it) }
        raw.stringField("type_of_use")?.let { rest += CampsiteAttribute(TYPE_OF_USE_LABEL, it) }

        return PromotedAttributes(
            firepit = firepit,
            picnicTable = picnicTable,
            adaAccessible = adaAccessible,
            maxCars = maxCars,
            drivewayLength = drivewayLength,
            maxRvLength = maxRvLength,
            attributes = rest,
        )
    }

    private fun booleanValue(value: String?): Boolean? =
        when (value?.lowercase()) {
            in yesValues -> true
            in noValues -> false
            else -> null
        }

    private fun leadingInt(value: String?): Int? = value?.let { leadingIntRegex.find(it)?.value?.toIntOrNull() }

    /**
     * Pull the FacilityID from the URL the fetcher captured. URL shape:
     *   .../api/camps/availability/campground/{FacilityID}/month?start_date=...
     * Returns null when the marker isn't found so the validator drops
     * the envelope rather than crashing the run.
     */
    private fun parseFacilityIdFromUrl(url: String): String? {
        val marker = "/campground/"
        val start = url.indexOf(marker).takeIf { it >= 0 } ?: return null
        val tail = url.substring(start + marker.length)
        val end = tail.indexOfAny(charArrayOf('/', '?'))
        val raw = if (end < 0) tail else tail.substring(0, end)
        return raw.takeIf { it.isNotEmpty() }
    }

    /** Return a copy of [obj] with synthetic key/value pairs added. */
    private fun withSynthetic(
        obj: JsonObject,
        values: Map<String, kotlinx.serialization.json.JsonElement>,
    ): JsonObject =
        buildJsonObject {
            for ((k, v) in obj) put(k, v)
            for ((k, v) in values) put(k, v)
        }

    /** The rec.gov attribute list split into typed columns plus whatever is left over. */
    private data class PromotedAttributes(
        val firepit: Boolean?,
        val picnicTable: Boolean?,
        val adaAccessible: Boolean?,
        val maxCars: Int?,
        val drivewayLength: Int?,
        val maxRvLength: Int?,
        val attributes: List<CampsiteAttribute>,
    )
}
