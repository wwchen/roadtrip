package ca.floo.roadtrip.models.domain

internal object CampflareUrls {
    private const val CAMPGROUND_URL_PREFIX = "https://campflare.com/campground"

    fun campground(campgroundId: String): String = "$CAMPGROUND_URL_PREFIX/$campgroundId"
}
