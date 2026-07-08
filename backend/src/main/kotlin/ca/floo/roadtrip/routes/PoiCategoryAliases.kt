package ca.floo.roadtrip.routes

private val POI_CATEGORY_ALIASES =
    mapOf(
        "planet-fitness" to "planet_fitness_location",
        "supercharger" to "tesla_supercharger",
    )

internal fun canonicalPoiCategory(category: String): String = POI_CATEGORY_ALIASES[category] ?: category

internal fun canonicalPoiCategories(categories: List<String>): List<String> =
    categories
        .map(::canonicalPoiCategory)
        .distinct()
