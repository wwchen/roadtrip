package ca.floo.roadtrip.service.etl.framework

import ca.floo.roadtrip.model.domain.CampsiteKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The kind table is a judgment call per vendor, so every vendor value the
 * design spec lists is asserted here — including the ones that are meant to
 * land on [CampsiteKind.OTHER].
 */
class CampsiteKindsTest {
    @Test
    fun `rec gov campsite types map by leading token`() {
        val expected =
            mapOf(
                "STANDARD NONELECTRIC" to CampsiteKind.STANDARD,
                "STANDARD ELECTRIC" to CampsiteKind.STANDARD,
                "TENT ONLY NONELECTRIC" to CampsiteKind.TENT,
                "RV NONELECTRIC" to CampsiteKind.RV,
                "RV ELECTRIC" to CampsiteKind.RV,
                "WALK TO" to CampsiteKind.WALK_IN,
                "HIKE TO" to CampsiteKind.WALK_IN,
                "GROUP STANDARD NONELECTRIC" to CampsiteKind.GROUP,
                "GROUP TENT ONLY AREA NONELECTRIC" to CampsiteKind.GROUP,
                "GROUP SHELTER NONELECTRIC" to CampsiteKind.GROUP,
                "GROUP PICNIC AREA" to CampsiteKind.GROUP,
                "EQUESTRIAN NONELECTRIC" to CampsiteKind.EQUESTRIAN,
                "CABIN NONELECTRIC" to CampsiteKind.CABIN,
                "CABIN ELECTRIC" to CampsiteKind.CABIN,
                "SHELTER NONELECTRIC" to CampsiteKind.CABIN,
                "OVERNIGHT SHELTER NONELECTRIC" to CampsiteKind.CABIN,
                "LOOKOUT" to CampsiteKind.CABIN,
                "YURT" to CampsiteKind.CABIN,
                "BOAT IN" to CampsiteKind.BOAT_IN,
                "MOORING" to CampsiteKind.BOAT_IN,
                "ANCHORAGE" to CampsiteKind.BOAT_IN,
                "PICNIC" to CampsiteKind.DAY_USE,
                "PARKING" to CampsiteKind.DAY_USE,
                "DAY USE" to CampsiteKind.DAY_USE,
                "ZONE" to CampsiteKind.BACKCOUNTRY,
            )

        assertEquals(expected, expected.keys.associateWith { CampsiteKinds.recgov(it).kind })
    }

    @Test
    fun `rec gov unclassifiable campsite types fall back to other`() {
        val unclassifiable = listOf(null, "", "   ", "MANAGEMENT", "SOMETHING NEW NONELECTRIC")

        assertEquals(
            unclassifiable.map { CampsiteKind.OTHER },
            unclassifiable.map { CampsiteKinds.recgov(it).kind },
        )
    }

    @Test
    fun `rec gov reads electric hookups off the trailing token`() {
        assertEquals(true, CampsiteKinds.recgov("STANDARD ELECTRIC").electric)
        assertEquals(true, CampsiteKinds.recgov("RV ELECTRIC").electric)
        assertEquals(false, CampsiteKinds.recgov("STANDARD NONELECTRIC").electric)
        assertEquals(false, CampsiteKinds.recgov("TENT ONLY NONELECTRIC").electric)
        assertEquals(null, CampsiteKinds.recgov("WALK TO").electric)
        assertEquals(null, CampsiteKinds.recgov("GROUP PICNIC AREA").electric)
        assertEquals(null, CampsiteKinds.recgov(null).electric)
    }

    @Test
    fun `campflare kinds map by exact value`() {
        val expected =
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

        assertEquals(expected, expected.keys.associateWith { CampsiteKinds.campflare(it) })
    }

    @Test
    fun `campflare unknown kinds fall back to other`() {
        val unclassifiable = listOf(null, "", "STANDARD", "houseboat")

        assertEquals(
            unclassifiable.map { CampsiteKind.OTHER },
            unclassifiable.map { CampsiteKinds.campflare(it) },
        )
    }

    @Test
    fun `aspira dictionary names map by exact name then prefix`() {
        val expected =
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
                "Backcountry Site" to CampsiteKind.BACKCOUNTRY,
                "Backcountry Zone" to CampsiteKind.BACKCOUNTRY,
                "Wilderness Camping" to CampsiteKind.BACKCOUNTRY,
                "Group Campground" to CampsiteKind.GROUP,
                "Group Camp" to CampsiteKind.GROUP,
                "Equestrian" to CampsiteKind.EQUESTRIAN,
                "Marina" to CampsiteKind.BOAT_IN,
                "Mooring Buoy" to CampsiteKind.BOAT_IN,
                "Marine Trail" to CampsiteKind.BOAT_IN,
                "Annual Marina" to CampsiteKind.BOAT_IN,
                "Day Use Facility" to CampsiteKind.DAY_USE,
                "Conference Facility" to CampsiteKind.DAY_USE,
                "Retreat Centre" to CampsiteKind.DAY_USE,
                "Daily Fishing" to CampsiteKind.DAY_USE,
                "Guided Event" to CampsiteKind.DAY_USE,
                "Hiking Trip" to CampsiteKind.DAY_USE,
                "Ferry" to CampsiteKind.DAY_USE,
            )

        assertEquals(expected, expected.keys.associateWith { CampsiteKinds.aspira(it) })
    }

    @Test
    fun `aspira names outside the table fall back to other`() {
        val unclassifiable = listOf(null, "", "Boat Launch", "campsite")

        assertEquals(
            unclassifiable.map { CampsiteKind.OTHER },
            unclassifiable.map { CampsiteKinds.aspira(it) },
        )
    }

    @Test
    fun `reserve california unit types map by exact value`() {
        val expected =
            mapOf(
                "Tent Site" to CampsiteKind.TENT,
                "Day Use" to CampsiteKind.DAY_USE,
                "site" to CampsiteKind.OTHER,
                "RV Site" to CampsiteKind.OTHER,
                "" to CampsiteKind.OTHER,
            )

        assertEquals(expected, expected.keys.associateWith { CampsiteKinds.reserveCalifornia(it) })
        assertEquals(CampsiteKind.OTHER, CampsiteKinds.reserveCalifornia(null))
    }

    @Test
    fun `wire values round-trip through fromWire and unknown wire values are rejected`() {
        assertEquals(
            CampsiteKind.entries.toList(),
            CampsiteKind.entries.map { CampsiteKind.fromWire(it.wire) },
        )
        assertEquals(null, CampsiteKind.fromWire("STANDARD NONELECTRIC"))
        assertEquals(null, CampsiteKind.fromWire("walk-in"))
    }
}
