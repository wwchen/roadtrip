package ca.floo.roadtrip.service.atlas

import ca.floo.roadtrip.model.domain.atlas.LandClass

/**
 * Buckets a campground's free-text managing agency (`management->>'agency'`)
 * into a [LandClass], checked in priority order: national-park agencies win over
 * the generic land keywords, and state/provincial parks are matched before the
 * federal open-land agencies. Keywords are matched case-insensitively as
 * substrings, since the source strings vary ("USDA Forest Service",
 * "California State Parks", "South Dakota Game, Fish and Parks", ...).
 */
internal object AtlasClassifier {
    private val NATIONAL_PARK_AGENCIES = listOf("national park service", "parks canada")

    private val STATE_PARK_AGENCIES =
        listOf(
            "state park",
            "provincial park",
            "state forest",
            "state recreation",
            "state game",
            "parks and wildlife",
            "game, fish and parks",
            "department of natural resources",
            "department of conservation",
            "conservation area",
            "bc parks",
            "alberta parks",
            "ontario parks",
            "sépaq",
            "sepaq",
        )

    private val OPEN_LAND_AGENCIES =
        listOf(
            "forest service",
            "national forest",
            "national grassland",
            "bureau of land management",
            "army corps of engineers",
            "fish and wildlife",
            "bureau of reclamation",
            "tennessee valley authority",
        )

    fun classify(agency: String?): LandClass {
        val normalized = agency?.lowercase()?.trim().orEmpty()
        if (normalized.isEmpty()) return LandClass.OTHER
        return when {
            NATIONAL_PARK_AGENCIES.any { normalized.contains(it) } -> LandClass.NATIONAL_PARK
            STATE_PARK_AGENCIES.any { normalized.contains(it) } -> LandClass.STATE_PARK
            OPEN_LAND_AGENCIES.any { normalized.contains(it) } -> LandClass.OPEN_LAND
            else -> LandClass.OTHER
        }
    }
}
