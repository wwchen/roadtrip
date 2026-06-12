package ca.floo.roadtrip.service.etl.aspira

import ca.floo.roadtrip.models.ValidationResult
import ca.floo.roadtrip.service.etl.InputBundle
import ca.floo.roadtrip.service.etl.SourceEtl
import ca.floo.roadtrip.service.etl.TransformCtx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

// Aspira /api/maps tree → flat list of bookable leaves.
//
// `/api/maps` returns a tree of map "nodes". Each node is one screen the
// booking SPA can render — a region overview, a campground site, a
// retreat-center building. The leaves we care about are the ones with
// `transactionLocationId` set: those are the records the deeplink builder
// keys off (RFC 0006). Internal nodes have null txnLoc and just steer
// navigation.
//
// Title resolution: the leaf's own `localizedValues` is the preferred
// source ("Tunnel Mountain Village I", "Cedar Spring and Christian
// Beach"). Some tenants (PC) leave that empty on the leaf itself and
// only fill it on the parent map's `mapLinks` entry pointing at the
// leaf — so we fall back to that. Culture filter is `startsWith("en")`,
// not equality, because PC uses "en-CA" and WA uses "en-US".
//
// One ETL class, three registry entries (WA / BC / PC). The host is
// implicit — every leaf this ETL emits belongs to the tenant whose
// /api/maps capture it parsed.
class AspiraLeavesEtl(
    override val etlSlug: String,
) : SourceEtl<AspiraMapsDto, AspiraLeavesPayload> {
    override fun parse(inputs: InputBundle): AspiraMapsDto {
        val envelope = inputs.soleEnvelopes().single()
        val maps = envelope.payload.jsonArray
        return AspiraMapsDto(maps = maps)
    }

    override fun validate(dto: AspiraMapsDto): ValidationResult<AspiraMapsDto> =
        if (dto.maps.isEmpty()) {
            ValidationResult.Bad(null, listOf("$etlSlug: empty /api/maps payload"))
        } else {
            ValidationResult.Ok(dto)
        }

    override fun transform(
        dto: AspiraMapsDto,
        ctx: TransformCtx,
    ): AspiraLeavesPayload =
        AspiraLeavesPayload(
            slug = etlSlug,
            leaves = AspiraLeavesWalk.walk(dto.maps),
        )
}

/** Just-parsed envelope, before we walk the tree. */
data class AspiraMapsDto(
    val maps: JsonArray,
) {
    val isEmpty: Boolean get() = maps.isEmpty()
}

/** Materialized intermediate output. Downstream join-by-name ETL consumes this. */
@Serializable
data class AspiraLeavesPayload(
    val slug: String,
    val leaves: List<AspiraLeaf>,
)

@Serializable
data class AspiraLeaf(
    val name: String,
    @kotlinx.serialization.SerialName("transaction_location_id") val transactionLocationId: Long,
    @kotlinx.serialization.SerialName("map_id") val mapId: Long,
    @kotlinx.serialization.SerialName("resource_location_id") val resourceLocationId: Long? = null,
    /** Title of the parent map node, when the leaf is a sub-area (PC backcountry). */
    @kotlinx.serialization.SerialName("parent_name") val parentName: String? = null,
)
