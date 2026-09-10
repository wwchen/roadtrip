package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampsiteKind

/** rec.gov's `campsite_type` carries the hookup fact in the same string as the type. */
data class RecGovKind(
    val kind: CampsiteKind,
    val electric: Boolean?,
)

/**
 * Vendor type string → [CampsiteKind]. One table per vendor, and the only
 * place a vendor's type vocabulary is spelled out; `V58__campsite_kind_wire.sql`
 * mirrors these rows in SQL for rows imported before the vocabulary existed.
 */
object CampsiteKinds {
    fun recgov(campsiteType: String?): RecGovKind {
        val tokens =
            campsiteType
                ?.trim()
                ?.uppercase()
                ?.split(" ")
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (tokens.isEmpty()) return RecGovKind(CampsiteKind.OTHER, null)
        val kind =
            recGovLeadingPhrases
                .firstOrNull { (phrase, _) -> tokens.take(phrase.size) == phrase }
                ?.second
                ?: CampsiteKind.OTHER
        return RecGovKind(kind, recGovElectric(tokens.last()))
    }

    fun campflare(kind: String?): CampsiteKind = campflareKinds[kind?.trim()] ?: CampsiteKind.OTHER

    fun aspira(category: String?): CampsiteKind {
        val name = category?.trim().orEmpty()
        if (name.isEmpty()) return CampsiteKind.OTHER
        aspiraExactNames[name]?.let { return it }
        return aspiraPrefixes.firstOrNull { (prefix, _) -> name.startsWith(prefix) }?.second ?: CampsiteKind.OTHER
    }

    fun reserveCalifornia(unitType: String?): CampsiteKind = reserveCaliforniaUnitTypes[unitType?.trim()] ?: CampsiteKind.OTHER

    private fun recGovElectric(lastToken: String): Boolean? =
        when (lastToken) {
            RECGOV_ELECTRIC_TOKEN -> true
            RECGOV_NONELECTRIC_TOKEN -> false
            else -> null
        }
}

private const val RECGOV_ELECTRIC_TOKEN = "ELECTRIC"
private const val RECGOV_NONELECTRIC_TOKEN = "NONELECTRIC"

/**
 * rec.gov leading phrases, first match wins. No phrase is a token-prefix of
 * another, so the order below is the spec's order rather than a precedence.
 */
private val recGovLeadingPhrases: List<Pair<List<String>, CampsiteKind>> =
    listOf(
        "GROUP" to CampsiteKind.GROUP,
        "TENT ONLY" to CampsiteKind.TENT,
        "RV" to CampsiteKind.RV,
        "CABIN" to CampsiteKind.CABIN,
        "YURT" to CampsiteKind.CABIN,
        "LOOKOUT" to CampsiteKind.CABIN,
        "OVERNIGHT SHELTER" to CampsiteKind.CABIN,
        "SHELTER" to CampsiteKind.CABIN,
        "WALK TO" to CampsiteKind.WALK_IN,
        "HIKE TO" to CampsiteKind.WALK_IN,
        "BOAT IN" to CampsiteKind.BOAT_IN,
        "MOORING" to CampsiteKind.BOAT_IN,
        "ANCHORAGE" to CampsiteKind.BOAT_IN,
        "EQUESTRIAN" to CampsiteKind.EQUESTRIAN,
        "STANDARD" to CampsiteKind.STANDARD,
        "ZONE" to CampsiteKind.BACKCOUNTRY,
        "PICNIC" to CampsiteKind.DAY_USE,
        "PARKING" to CampsiteKind.DAY_USE,
        "DAY USE" to CampsiteKind.DAY_USE,
    ).map { (phrase, kind) -> phrase.split(" ") to kind }

private val campflareKinds: Map<String, CampsiteKind> =
    mapOf(
        "standard" to CampsiteKind.STANDARD,
        "tent-only" to CampsiteKind.TENT,
        "rv" to CampsiteKind.RV,
        "cabin" to CampsiteKind.CABIN,
        "group" to CampsiteKind.GROUP,
        "walk-to" to CampsiteKind.WALK_IN,
        "water-access" to CampsiteKind.BOAT_IN,
        "equestrian" to CampsiteKind.EQUESTRIAN,
        "management" to CampsiteKind.OTHER,
    )

/** Aspira resource-category dictionary names, exactly as the tenant dictionaries spell them. */
private val aspiraExactNames: Map<String, CampsiteKind> =
    mapOf(
        "Campsite" to CampsiteKind.STANDARD,
        "Campsite/Seasonal" to CampsiteKind.STANDARD,
        "Overflow" to CampsiteKind.STANDARD,
        "Cabin" to CampsiteKind.CABIN,
        "Rustic Cabin" to CampsiteKind.CABIN,
        "Deluxe Cabin" to CampsiteKind.CABIN,
        "Backcountry Cabin" to CampsiteKind.CABIN,
        "Yurt" to CampsiteKind.CABIN,
        "oTENTik" to CampsiteKind.CABIN,
        "Ôasis" to CampsiteKind.CABIN,
        "MicrOcube" to CampsiteKind.CABIN,
        "Teepee" to CampsiteKind.CABIN,
        "Prospector Tent" to CampsiteKind.CABIN,
        "Platform Tent" to CampsiteKind.CABIN,
        "Adirondack" to CampsiteKind.CABIN,
        "Equipped Camping" to CampsiteKind.CABIN,
        "Vacation House" to CampsiteKind.CABIN,
        "Equestrian" to CampsiteKind.EQUESTRIAN,
        "Marina" to CampsiteKind.BOAT_IN,
        "Mooring Buoy" to CampsiteKind.BOAT_IN,
        "Marine Trail" to CampsiteKind.BOAT_IN,
        "Annual Marina" to CampsiteKind.BOAT_IN,
        "Daily Fishing" to CampsiteKind.DAY_USE,
        "Guided Event" to CampsiteKind.DAY_USE,
        "Hiking Trip" to CampsiteKind.DAY_USE,
        "Ferry" to CampsiteKind.DAY_USE,
    )

/** Checked only after [aspiraExactNames], so "Backcountry Cabin" stays a cabin. */
private val aspiraPrefixes: List<Pair<String, CampsiteKind>> =
    listOf(
        "Backcountry" to CampsiteKind.BACKCOUNTRY,
        "Wilderness" to CampsiteKind.BACKCOUNTRY,
        "Group" to CampsiteKind.GROUP,
        "Day Use" to CampsiteKind.DAY_USE,
        "Conference" to CampsiteKind.DAY_USE,
        "Retreat" to CampsiteKind.DAY_USE,
    )

private val reserveCaliforniaUnitTypes: Map<String, CampsiteKind> =
    mapOf(
        "Tent Site" to CampsiteKind.TENT,
        "Day Use" to CampsiteKind.DAY_USE,
    )
