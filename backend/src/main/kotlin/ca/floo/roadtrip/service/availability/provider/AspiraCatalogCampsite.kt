package ca.floo.roadtrip.service.availability.provider

internal data class AspiraCatalogCampsite(
    val campsiteId: Long,
    val resourceId: String,
    val mapId: Int,
    /**
     * The same vendor identifier as [resourceId], as an Int — both come from the
     * catalog ref's `resourceLocationId` field, which for a campsite holds its
     * *resource* id, not the id of the location it sits under. A campground's
     * booking ref carries the location id, and the two are different namespaces,
     * so this cannot be compared against one: campsites carry nothing to narrow
     * an occupancy call's targets by.
     */
    val resourceLocationId: Int? = null,
)
