package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampsiteKind

/** Some vendors carry the hookup fact in the same string as the type. */
data class VendorKind(
    val kind: CampsiteKind,
    val electric: Boolean?,
    val warning: String? = null,
)

/**
 * Vendor type string → [CampsiteKind]. One table per vendor, and the only
 * place a vendor's type vocabulary is spelled out.
 */
object CampsiteKinds {
    fun recgov(campsiteType: String?): VendorKind {
        val tokens =
            campsiteType
                ?.trim()
                ?.uppercase()
                ?.split(" ")
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (tokens.isEmpty()) return unlisted(RECGOV_FIELD, campsiteType, null)
        val electric = recGovElectric(tokens.last())
        return recGovLeadingPhrases
            .firstOrNull { (phrase, _) -> tokens.take(phrase.size) == phrase }
            ?.let { (_, kind) -> VendorKind(kind, electric) }
            ?: unlisted(RECGOV_FIELD, campsiteType, electric)
    }

    fun campflare(kind: String?): VendorKind =
        campflareKinds[kind?.trim()]?.let { VendorKind(it, null) } ?: unlisted(CAMPFLARE_FIELD, kind, null)

    fun aspira(category: String?): VendorKind {
        val name = category?.trim().orEmpty()
        val kind =
            aspiraExactNames[name]
                ?: aspiraPrefixes.firstOrNull { (prefix, _) -> name.isNotEmpty() && name.startsWith(prefix) }?.second
        return kind?.let { VendorKind(it, null) } ?: unlisted(ASPIRA_FIELD, category, null)
    }

    fun reserveCalifornia(unitType: String?): VendorKind {
        val name = unitType?.trim()?.lowercase().orEmpty()
        if (name.isEmpty()) return unlisted(RC_FIELD, unitType, null)
        val electric = true.takeIf { RC_HOOKUP_SUBSTRING in name && RC_ELECTRIC_SUBSTRING in name }
        return reserveCaliforniaSubstrings
            .firstOrNull { (substrings, _) -> substrings.any { it in name } }
            ?.let { (_, kind) -> VendorKind(kind, electric) }
            ?: unlisted(RC_FIELD, unitType, electric)
    }

    private fun recGovElectric(lastToken: String): Boolean? =
        when (lastToken) {
            RECGOV_ELECTRIC_TOKEN -> true
            RECGOV_NONELECTRIC_TOKEN -> false
            else -> null
        }

    private fun unlisted(
        field: String,
        value: String?,
        electric: Boolean?,
    ): VendorKind {
        val listed = value?.trim().orEmpty()
        val warning = if (listed.isEmpty()) "no $field" else "unlisted $field '$listed'"
        return VendorKind(CampsiteKind.OTHER, electric, warning)
    }
}

private const val RECGOV_FIELD = "rec.gov campsite_type"
private const val CAMPFLARE_FIELD = "Campflare kind"
private const val ASPIRA_FIELD = "Aspira resource category"
private const val RC_FIELD = "ReserveCalifornia unit type"
private const val RECGOV_ELECTRIC_TOKEN = "ELECTRIC"
private const val RECGOV_NONELECTRIC_TOKEN = "NONELECTRIC"

private val recGovLeadingPhrases: List<Pair<List<String>, CampsiteKind>> =
    listOf(
        "GROUP PICNIC" to CampsiteKind.DAY_USE,
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
        .sortedByDescending { (phrase, _) -> phrase.size }

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
        "Backcountry Yurt" to CampsiteKind.CABIN,
        "Cama Beach Cabin" to CampsiteKind.CABIN,
        "Cama Beach Cabin Category" to CampsiteKind.CABIN,
        "Elfin Lakes Shelter" to CampsiteKind.CABIN,
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
        "Boat In" to CampsiteKind.BOAT_IN,
        "Marina" to CampsiteKind.BOAT_IN,
        "Mooring Buoy" to CampsiteKind.BOAT_IN,
        "Marine Trail" to CampsiteKind.BOAT_IN,
        "Annual Marina" to CampsiteKind.BOAT_IN,
        "Daily Fishing" to CampsiteKind.DAY_USE,
        "Guided Event" to CampsiteKind.DAY_USE,
        "Hiking Trip" to CampsiteKind.DAY_USE,
        "Ferry" to CampsiteKind.DAY_USE,
        "Group Picnic Area" to CampsiteKind.DAY_USE,
        "Parking" to CampsiteKind.DAY_USE,
        "Shuttle" to CampsiteKind.DAY_USE,
        "Guided Hike" to CampsiteKind.DAY_USE,
        "Learn-to Activity" to CampsiteKind.DAY_USE,
        "Day Runner Hike" to CampsiteKind.DAY_USE,
        "Backpacking Trip" to CampsiteKind.BACKCOUNTRY,
        "Access Point" to CampsiteKind.BACKCOUNTRY,
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

private const val RC_HOOKUP_SUBSTRING = "hook up"
private const val RC_ELECTRIC_SUBSTRING = "(e"

/**
 * ReserveCalifornia unit-type names are free-text (49 of them in the local
 * catalog), so they are matched by substring of the lowercased name, first rule
 * wins. Order is the precedence: `Equestrain Group Tent Primitive Campsite` is a
 * group site, `Tent Only - Walk-In` a walk-in, `Premium Campsite` a standard one.
 */
private val reserveCaliforniaSubstrings: List<Pair<List<String>, CampsiteKind>> =
    listOf(
        listOf("day use", "dailyuse") to CampsiteKind.DAY_USE,
        listOf("group") to CampsiteKind.GROUP,
        listOf("equestrian", "equestrain") to CampsiteKind.EQUESTRIAN,
        listOf("cabin", "cottage", "yurt", "dorm") to CampsiteKind.CABIN,
        listOf("boat in", "floating camp") to CampsiteKind.BOAT_IN,
        listOf("hike", "bike", "walk-in") to CampsiteKind.WALK_IN,
        listOf(RC_HOOKUP_SUBSTRING) to CampsiteKind.RV,
        listOf("tent") to CampsiteKind.TENT,
        listOf("campsite") to CampsiteKind.STANDARD,
    )
