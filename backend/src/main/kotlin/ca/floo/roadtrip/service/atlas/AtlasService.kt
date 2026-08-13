package ca.floo.roadtrip.service.atlas

import ca.floo.roadtrip.model.api.AtlasNodeDto
import ca.floo.roadtrip.model.api.AtlasNodeKind
import ca.floo.roadtrip.model.api.AtlasNodeResponseDto
import ca.floo.roadtrip.model.domain.atlas.LandClass
import ca.floo.roadtrip.repo.AtlasRepo

internal const val ATLAS_TEASER_COUNT = 3
internal const val ATLAS_CAMPGROUND_LIMIT = 2_000
internal const val ATLAS_CAMPSITE_LIMIT = 2_000
private const val UNKNOWN_REGION_KEY = "unknown"
private const val UNKNOWN_REGION_LABEL = "Unknown location"

/**
 * Builds the Atlas index tree one level at a time from [AtlasRepo]. Levels are
 * addressed by a dot-delimited path: "" -> regions, "UT" -> classifications,
 * "UT.national" -> campgrounds, "UT.national.8252" -> campsites. Classification
 * is derived here via [AtlasClassifier]; the repo stays agency-based.
 */
internal class AtlasService(
    private val repo: AtlasRepo,
) {
    fun node(path: String): AtlasNodeResponseDto {
        val segments = path.split(".").map { it.trim() }.filter { it.isNotEmpty() }
        val children =
            when (segments.size) {
                0 -> regionNodes()
                1 -> classificationNodes(regionKey = segments[0])
                2 -> campgroundNodes(regionKey = segments[0], classKey = segments[1])
                3 -> campsiteNodes(parentPath = path, campgroundId = segments[2].toLongOrNull())
                else -> emptyList()
            }
        return AtlasNodeResponseDto(path = path, children = children)
    }

    private fun regionNodes(): List<AtlasNodeDto> {
        val counts = repo.regionAgencyCounts()
        return counts
            .groupBy { it.region }
            .map { (region, rows) ->
                val total = rows.sumOf { it.campgroundCount }
                val classesPresent =
                    LandClass.entries.filter { cls ->
                        rows.any { it.campgroundCount > 0 && AtlasClassifier.classify(it.agency) == cls }
                    }
                AtlasNodeDto(
                    key = region ?: UNKNOWN_REGION_KEY,
                    label = region?.let(AtlasRegionNames::label) ?: UNKNOWN_REGION_LABEL,
                    kind = AtlasNodeKind.REGION,
                    childCount = total,
                    hasChildren = total > 0,
                    teaser = classesPresent.map { it.label },
                )
            }.sortedWith(compareBy({ it.key == UNKNOWN_REGION_KEY }, { it.label }))
    }

    private fun classificationNodes(regionKey: String): List<AtlasNodeDto> {
        val region = regionKey.toRegionOrNull()
        val rows = repo.regionAgencyCounts().filter { it.region == region }
        if (rows.isEmpty()) return emptyList()

        val namesByClass =
            repo
                .agencyNameSamples(region, ATLAS_TEASER_COUNT * 2)
                .groupBy { AtlasClassifier.classify(it.agency) }
                .mapValues { (_, v) -> v.map { it.name } }
        val countByClass =
            rows
                .groupBy { AtlasClassifier.classify(it.agency) }
                .mapValues { (_, v) -> v.sumOf { it.campgroundCount } }

        return LandClass.entries
            .filter { (countByClass[it] ?: 0) > 0 }
            .map { cls ->
                AtlasNodeDto(
                    key = "$regionKey.${cls.key}",
                    label = cls.label,
                    kind = AtlasNodeKind.CLASSIFICATION,
                    childCount = countByClass.getValue(cls),
                    hasChildren = true,
                    teaser = (namesByClass[cls] ?: emptyList()).distinct().take(ATLAS_TEASER_COUNT),
                )
            }
    }

    private fun campgroundNodes(
        regionKey: String,
        classKey: String,
    ): List<AtlasNodeDto> {
        val cls = LandClass.fromKey(classKey) ?: return emptyList()
        val region = regionKey.toRegionOrNull()
        val rows = repo.regionAgencyCounts().filter { it.region == region }
        val agencies =
            rows.mapNotNull { it.agency }.distinct().filter { AtlasClassifier.classify(it) == cls }
        val includeNull = cls == LandClass.OTHER && rows.any { it.agency == null }
        if (agencies.isEmpty() && !includeNull) return emptyList()

        return repo
            .campgrounds(region, agencies, includeNull, ATLAS_CAMPGROUND_LIMIT)
            .map { cg ->
                AtlasNodeDto(
                    key = "$regionKey.$classKey.${cg.id}",
                    label = cg.name,
                    kind = AtlasNodeKind.CAMPGROUND,
                    childCount = cg.siteCount,
                    hasChildren = cg.siteCount > 0,
                    poiId = cg.poiId,
                )
            }
    }

    private fun campsiteNodes(
        parentPath: String,
        campgroundId: Long?,
    ): List<AtlasNodeDto> {
        if (campgroundId == null) return emptyList()
        return repo.campsites(campgroundId, ATLAS_CAMPSITE_LIMIT).map { site ->
            AtlasNodeDto(
                key = "$parentPath.${site.id}",
                label = site.loopName?.takeIf { it.isNotBlank() }?.let { "$it · ${site.name}" } ?: site.name,
                kind = AtlasNodeKind.CAMPSITE,
                childCount = 0,
                hasChildren = false,
            )
        }
    }

    private fun String.toRegionOrNull(): String? = takeIf { it != UNKNOWN_REGION_KEY }
}
